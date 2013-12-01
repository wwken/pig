/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.pig.backend.hadoop.executionengine.tez;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.hadoop.conf.Configuration;
import org.apache.pig.PigException;
import org.apache.pig.backend.executionengine.ExecException;
import org.apache.pig.backend.hadoop.HDataType;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigMapReduce;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.POStatus;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.PhysicalOperator;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.Result;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.plans.PhysicalPlan;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POLocalRearrange;
import org.apache.pig.backend.hadoop.executionengine.shims.HadoopShims;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.util.PlanHelper;
import org.apache.pig.data.SchemaTupleBackend;
import org.apache.pig.data.Tuple;
import org.apache.pig.data.TupleFactory;
import org.apache.pig.impl.PigContext;
import org.apache.pig.impl.io.NullableTuple;
import org.apache.pig.impl.io.PigNullableWritable;
import org.apache.pig.impl.util.ObjectSerializer;
import org.apache.pig.impl.util.UDFContext;
import org.apache.tez.common.TezUtils;
import org.apache.tez.mapreduce.output.MROutput;
import org.apache.tez.runtime.api.Event;
import org.apache.tez.runtime.api.LogicalIOProcessor;
import org.apache.tez.runtime.api.LogicalInput;
import org.apache.tez.runtime.api.LogicalOutput;
import org.apache.tez.runtime.api.TezProcessorContext;
import org.apache.tez.runtime.library.api.KeyValueWriter;
import org.apache.tez.runtime.library.output.OnFileSortedOutput;
import org.apache.tez.runtime.library.output.OnFileUnorderedKVOutput;

public class PigProcessor implements LogicalIOProcessor {
    // Names of the properties that store serialized physical plans
    public static final String PLAN = "pig.exec.tez.plan";
    public static final String COMBINE_PLAN = "pig.exec.tez.combine.plan";
    private static TupleFactory tupleFactory = TupleFactory.getInstance();

    private PhysicalPlan execPlan;

    private Set<MROutput> fileOutputs = new HashSet<MROutput>();

    private Map<String, KeyValueWriter> writers = new HashMap<String, KeyValueWriter>();

    private PhysicalOperator leaf;

    private enum OUTPUT_TYPE {
        FILE,
        SHUFFLE,
        BROADCAST,
    }
    private OUTPUT_TYPE outputType;
    private byte keyType;

    private Configuration conf;

    @Override
    public void initialize(TezProcessorContext processorContext)
            throws Exception {
        byte[] payload = processorContext.getUserPayload();
        conf = TezUtils.createConfFromUserPayload(payload);
        PigContext pc = (PigContext) ObjectSerializer.deserialize(conf.get("pig.pigContext"));

        UDFContext.getUDFContext().addJobConf(conf);
        UDFContext.getUDFContext().deserialize();

        String execPlanString = conf.get(PLAN);
        execPlan = (PhysicalPlan) ObjectSerializer.deserialize(execPlanString);
        SchemaTupleBackend.initialize(conf, pc);
        PigMapReduce.sJobContext = HadoopShims.createJobContext(conf, new org.apache.hadoop.mapreduce.JobID());
    }

    @Override
    public void handleEvents(List<Event> processorEvents) {
        // TODO Auto-generated method stub

    }
    @Override
    public void close() throws Exception {
        // TODO Auto-generated method stub

    }
    @Override
    public void run(Map<String, LogicalInput> inputs,
            Map<String, LogicalOutput> outputs) throws Exception {

        initializeInputs(inputs);

        initializeOutputs(outputs);

        List<PhysicalOperator> leaves = null;

        if (!execPlan.isEmpty()) {
            leaves = execPlan.getLeaves();
            // TODO: Pull from all leaves when there are multiple leaves/outputs
            leaf = leaves.get(0);
            // TODO: Remove in favor of maps/indexed arrays in a multi-output world
            if (outputType == OUTPUT_TYPE.SHUFFLE || outputType == OUTPUT_TYPE.BROADCAST) {
                keyType = ((POLocalRearrange)leaf).getKeyType();
            }
        }

        runPipeline(leaf);

        for (MROutput fileOutput : fileOutputs){
            fileOutput.commit();
        }
    }

    private void initializeInputs(Map<String, LogicalInput> inputs)
            throws IOException {
        // getPhysicalOperators only accept C extends PhysicalOperator, so we can't change it to look for TezLoad
        // TODO: Change that.
        LinkedList<POSimpleTezLoad> tezLds = PlanHelper.getPhysicalOperators(execPlan, POSimpleTezLoad.class);
        for (POSimpleTezLoad tezLd : tezLds){
            tezLd.attachInputs(inputs, conf);
        }
        LinkedList<POShuffleTezLoad> shuffles = PlanHelper.getPhysicalOperators(execPlan, POShuffleTezLoad.class);
        for (POShuffleTezLoad shuffle : shuffles){
            shuffle.attachInputs(inputs, conf);
        }
        LinkedList<POBroadcastTezLoad> broadcasts = PlanHelper.getPhysicalOperators(execPlan, POBroadcastTezLoad.class);
        for (POBroadcastTezLoad broadcast : broadcasts){
            broadcast.attachInputs(inputs, conf);
        }
    }

    private void initializeOutputs(Map<String, LogicalOutput> outputs) throws Exception {
        for (Entry<String, LogicalOutput> entry : outputs.entrySet()){
            String name = entry.getKey();
            LogicalOutput logicalOutput = entry.getValue();
            if (logicalOutput instanceof MROutput){
                MROutput mrOut = (MROutput) logicalOutput;
                fileOutputs.add(mrOut);
                writers.put(name, mrOut.getWriter());
                outputType = OUTPUT_TYPE.FILE;
            } else if (logicalOutput instanceof OnFileSortedOutput){
                OnFileSortedOutput onFileOut = (OnFileSortedOutput) logicalOutput;
                writers.put(name, onFileOut.getWriter());
                outputType = OUTPUT_TYPE.SHUFFLE;
            } else if (logicalOutput instanceof OnFileUnorderedKVOutput){
                OnFileUnorderedKVOutput onFileOut = (OnFileUnorderedKVOutput) logicalOutput;
                writers.put(name, onFileOut.getWriter());
                outputType = OUTPUT_TYPE.BROADCAST;
            }
        }
    }

    protected void runPipeline(PhysicalOperator leaf) throws IOException, InterruptedException {
        while(true){
            Result res = leaf.getNextTuple();
            if(res.returnStatus==POStatus.STATUS_OK){
                writeResult((Tuple)res.result);
                continue;
            }

            if(res.returnStatus==POStatus.STATUS_EOP) {
                return;
            }

            if(res.returnStatus==POStatus.STATUS_NULL)
                continue;

            if(res.returnStatus==POStatus.STATUS_ERR){
                String errMsg;
                if(res.result != null) {
                    errMsg = "Received Error while " +
                            "processing the map plan: " + res.result;
                } else {
                    errMsg = "Received Error while " +
                            "processing the map plan.";
                }

                int errCode = 2055;
                ExecException ee = new ExecException(errMsg, errCode, PigException.BUG);
                throw ee;
            }
        }
    }

    private void writeResult(Tuple result) throws IOException {
        switch (outputType) {
            case FILE: {
                writers.values().iterator().next().write(null, result);
                break;
            }
            case SHUFFLE: {
                Byte index = (Byte)result.get(0);
                PigNullableWritable key =
                    HDataType.getWritableComparableTypes(result.get(1), keyType);
                NullableTuple val = new NullableTuple((Tuple)result.get(2));

                // Both the key and the value need the index.  The key needs it so
                // that it can be sorted on the index in addition to the key
                // value.  The value needs it so that POPackage can properly
                // assign the tuple to its slot in the projection.
                key.setIndex(index);
                val.setIndex(index);
                writers.values().iterator().next().write(key, val);
                break;
            }
            case BROADCAST: {
                Byte index = (Byte)result.get(0);
                // Key isn't used by broadcast edge, so it can be an empty tuple.
                Tuple nullTuple = tupleFactory.newTuple();
                PigNullableWritable key =
                        HDataType.getWritableComparableTypes(nullTuple, keyType);
                NullableTuple val = new NullableTuple((Tuple)result.get(1));
                key.setIndex(index);
                val.setIndex(index);
                writers.values().iterator().next().write(key, val);
                break;
            }
            default: {
                throw new IOException("Unkonwn output type: " + outputType);
            }
        }
    }
}
