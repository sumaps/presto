/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.operator.aggregation;

import com.facebook.presto.block.Block;
import com.facebook.presto.block.BlockBuilder;
import com.facebook.presto.block.BlockCursor;
import com.facebook.presto.operator.GroupByIdBlock;
import com.facebook.presto.operator.Page;
import com.facebook.presto.tuple.TupleInfo;
import com.facebook.presto.tuple.TupleInfo.Type;
import com.facebook.presto.util.array.ObjectBigArray;
import com.google.common.collect.ImmutableList;
import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;
import io.airlift.slice.SliceInput;
import io.airlift.stats.QuantileDigest;

import java.util.List;

import static com.facebook.presto.tuple.TupleInfo.SINGLE_LONG;
import static com.facebook.presto.tuple.TupleInfo.SINGLE_VARBINARY;
import static com.facebook.presto.tuple.TupleInfo.Type.DOUBLE;
import static com.facebook.presto.tuple.TupleInfo.Type.FIXED_INT_64;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static io.airlift.slice.SizeOf.SIZE_OF_DOUBLE;

public class LongApproximatePercentileAggregation
        implements AggregationFunction
{
    public static final LongApproximatePercentileAggregation INSTANCE = new LongApproximatePercentileAggregation();

    @Override
    public List<Type> getParameterTypes()
    {
        return ImmutableList.of(FIXED_INT_64, DOUBLE);
    }

    @Override
    public TupleInfo getFinalTupleInfo()
    {
        return SINGLE_LONG;
    }

    @Override
    public TupleInfo getIntermediateTupleInfo()
    {
        return SINGLE_VARBINARY;
    }

    @Override
    public LongApproximatePercentileGroupedAccumulator createGroupedAggregation(int[] argumentChannels)
    {
        return new LongApproximatePercentileGroupedAccumulator(argumentChannels[0], argumentChannels[1]);
    }

    @Override
    public GroupedAccumulator createGroupedIntermediateAggregation()
    {
        return new LongApproximatePercentileGroupedAccumulator(-1);
    }

    public static class LongApproximatePercentileGroupedAccumulator
            implements GroupedAccumulator
    {
        private final int valueChannel;
        private final int percentileChannel;
        private final ObjectBigArray<DigestAndPercentile> digests;

        public LongApproximatePercentileGroupedAccumulator(int valueChannel, int percentileChannel)
        {
            this.digests = new ObjectBigArray<>();
            this.valueChannel = valueChannel;
            this.percentileChannel = percentileChannel;
        }

        public LongApproximatePercentileGroupedAccumulator(int intermediateChannel)
        {
            this.digests = new ObjectBigArray<>();
            this.valueChannel = intermediateChannel;
            this.percentileChannel = -1;
        }

        @Override
        public long getEstimatedSize()
        {
            return 0;
        }

        @Override
        public TupleInfo getFinalTupleInfo()
        {
            return SINGLE_LONG;
        }

        @Override
        public TupleInfo getIntermediateTupleInfo()
        {
            return SINGLE_VARBINARY;
        }

        @Override
        public void addInput(GroupByIdBlock groupIdsBlock, Page page)
        {
            checkArgument(percentileChannel != -1, "Raw input is not allowed for a final aggregation");

            digests.ensureCapacity(groupIdsBlock.getGroupCount());

            BlockCursor values = page.getBlock(valueChannel).cursor();
            BlockCursor percentiles = page.getBlock(percentileChannel).cursor();

            for (int position = 0; position < groupIdsBlock.getPositionCount(); position++) {
                checkState(values.advanceNextPosition());
                checkState(percentiles.advanceNextPosition());

                long groupId = groupIdsBlock.getGroupId(position);

                // skip null values
                if (!values.isNull(0)) {

                    DigestAndPercentile currentValue = digests.get(groupId);
                    if (currentValue == null) {
                        currentValue = new DigestAndPercentile(new QuantileDigest(0.01));
                        digests.set(groupId, currentValue);
                    }

                    long value = values.getLong(0);
                    currentValue.getDigest().add(value);


                    // use last non-null percentile
                    if (!percentiles.isNull(0)) {
                        currentValue.setPercentile(percentiles.getDouble(0));
                    }
                }
            }
            checkState(!values.advanceNextPosition());
            checkState(!percentiles.advanceNextPosition());
        }

        @Override
        public void addIntermediate(GroupByIdBlock groupIdsBlock, Block block)
        {
            checkArgument(percentileChannel == -1, "Intermediate input is only allowed for a final aggregation");

            digests.ensureCapacity(groupIdsBlock.getGroupCount());

            BlockCursor intermediates = block.cursor();

            for (int position = 0; position < groupIdsBlock.getPositionCount(); position++) {
                checkState(intermediates.advanceNextPosition());

                if (!intermediates.isNull(0)) {
                    long groupId = groupIdsBlock.getGroupId(position);

                    DigestAndPercentile currentValue = digests.get(groupId);
                    if (currentValue == null) {
                        currentValue = new DigestAndPercentile(new QuantileDigest(0.01));
                        digests.set(groupId, currentValue);
                    }

                    SliceInput input = intermediates.getSlice(0).getInput();
                    currentValue.getDigest().merge(QuantileDigest.deserialize(input));
                    currentValue.setPercentile(input.readDouble());
                }
            }
        }

        @Override
        public void evaluateIntermediate(int groupId, BlockBuilder output)
        {
            DigestAndPercentile currentValue = digests.get((long) groupId);
            if (currentValue == null || currentValue.getDigest().getCount() == 0.0) {
                output.appendNull();
            }
            else {
                DynamicSliceOutput sliceOutput = new DynamicSliceOutput(currentValue.getDigest().estimatedSerializedSizeInBytes());
                currentValue.getDigest().serialize(sliceOutput);
                sliceOutput.appendDouble(currentValue.getPercentile());

                output.append(sliceOutput.slice());
            }
        }

        @Override
        public void evaluateFinal(int groupId, BlockBuilder output)
        {
            DigestAndPercentile currentValue = digests.get((long) groupId);
            if (currentValue == null || currentValue.getDigest().getCount() == 0.0) {
                output.appendNull();
            }
            else {
                checkState(currentValue.getPercentile() != -1.0, "Percentile is missing");
                output.append(currentValue.getDigest().getQuantile(currentValue.getPercentile()));
            }
        }
    }

    @Override
    public LongApproximatePercentileAccumulator createAggregation(int... argumentChannels)
    {
        return new LongApproximatePercentileAccumulator(argumentChannels[0], argumentChannels[1]);
    }

    @Override
    public LongApproximatePercentileAccumulator createIntermediateAggregation()
    {
        return new LongApproximatePercentileAccumulator(-1, -1);
    }

    public static class LongApproximatePercentileAccumulator
            implements Accumulator
    {
        private final int valueChannel;
        private final int percentileChannel;

        private final QuantileDigest digest = new QuantileDigest(0.01);
        private double percentile = -1;

        public LongApproximatePercentileAccumulator(int valueChannel, int percentileChannel)
        {
            this.valueChannel = valueChannel;
            this.percentileChannel = percentileChannel;
        }

        @Override
        public TupleInfo getFinalTupleInfo()
        {
            return SINGLE_LONG;
        }

        @Override
        public TupleInfo getIntermediateTupleInfo()
        {
            return SINGLE_VARBINARY;
        }

        @Override
        public void addInput(Page page)
        {
            checkArgument(valueChannel != -1, "Raw input is not allowed for a final aggregation");

            BlockCursor values = page.getBlock(valueChannel).cursor();
            BlockCursor percentiles = page.getBlock(percentileChannel).cursor();

            for (int position = 0; position < page.getPositionCount(); position++) {
                checkState(values.advanceNextPosition());
                checkState(percentiles.advanceNextPosition());

                if (!values.isNull(0)) {
                    long value = values.getLong(0);
                    digest.add(value);


                    // use last non-null percentile
                    if (!percentiles.isNull(0)) {
                        percentile = percentiles.getDouble(0);
                    }
                }
            }
        }

        @Override
        public void addIntermediate(Block block)
        {
            checkArgument(valueChannel == -1, "Intermediate input is only allowed for a final aggregation");

            BlockCursor intermediates = block.cursor();

            for (int position = 0; position < block.getPositionCount(); position++) {
                checkState(intermediates.advanceNextPosition());
                if (!intermediates.isNull(0)) {
                    SliceInput input = intermediates.getSlice(0).getInput();
                    // read digest
                    digest.merge(QuantileDigest.deserialize(input));
                    // read percentile
                    percentile = input.readDouble();
                }
            }
        }

        @Override
        public final Block evaluateIntermediate()
        {
            BlockBuilder out = new BlockBuilder(getIntermediateTupleInfo());
            if (digest.getCount() == 0.0) {
                out.appendNull();
            }
            else {
                DynamicSliceOutput sliceOutput = new DynamicSliceOutput(digest.estimatedSerializedSizeInBytes() + SIZE_OF_DOUBLE);
                // write digest
                digest.serialize(sliceOutput);
                // write percentile
                sliceOutput.appendDouble(percentile);

                Slice slice = sliceOutput.slice();
                out.append(slice);
            }
            return out.build();
        }

        @Override
        public final Block evaluateFinal()
        {
            BlockBuilder out = new BlockBuilder(getFinalTupleInfo());

            if (digest.getCount() == 0.0) {
                out.appendNull();
            }
            else {
                checkState(percentile != -1.0, "Percentile is missing");
                out.append(digest.getQuantile(percentile));
            }

            return out.build();
        }
    }

    public static final class DigestAndPercentile
    {
        private final QuantileDigest digest;
        private double percentile = -1;

        public DigestAndPercentile(QuantileDigest digest)
        {
            this.digest = digest;
        }

        public QuantileDigest getDigest()
        {
            return digest;
        }

        public double getPercentile()
        {
            return percentile;
        }

        public void setPercentile(double percentile)
        {
            this.percentile = percentile;
        }
    }
}
