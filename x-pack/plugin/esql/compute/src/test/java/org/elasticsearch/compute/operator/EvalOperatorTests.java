/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator;

import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.LongVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.core.Tuple;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static org.hamcrest.Matchers.equalTo;

public class EvalOperatorTests extends OperatorTestCase {
    @Override
    protected SourceOperator simpleInput(BlockFactory blockFactory, int end) {
        return new TupleBlockSourceOperator(blockFactory, LongStream.range(0, end).mapToObj(l -> Tuple.tuple(l, end - l)));
    }

    record Addition(DriverContext driverContext, int lhs, int rhs) implements EvalOperator.ExpressionEvaluator {
        @Override
        public Block.Ref eval(Page page) {
            LongVector lhsVector = page.<LongBlock>getBlock(0).asVector();
            LongVector rhsVector = page.<LongBlock>getBlock(1).asVector();
            try (LongVector.FixedBuilder result = LongVector.newVectorFixedBuilder(page.getPositionCount(), driverContext.blockFactory())) {
                for (int p = 0; p < page.getPositionCount(); p++) {
                    result.appendLong(lhsVector.getLong(p) + rhsVector.getLong(p));
                }
                return Block.Ref.floating(result.build().asBlock());
            }
        }

        @Override
        public String toString() {
            return "Addition[lhs=" + lhs + ", rhs=" + rhs + ']';
        }

        @Override
        public void close() {}
    }

    record LoadFromPage(int channel) implements EvalOperator.ExpressionEvaluator {
        @Override
        public Block.Ref eval(Page page) {
            return new Block.Ref(page.getBlock(channel), page);
        }

        @Override
        public void close() {}
    }

    @Override
    protected Operator.OperatorFactory simple(BigArrays bigArrays) {
        return new EvalOperator.EvalOperatorFactory(dvrCtx -> new Addition(dvrCtx, 0, 1));
    }

    @Override
    protected String expectedDescriptionOfSimple() {
        return "EvalOperator[evaluator=Addition[lhs=0, rhs=1]]";
    }

    @Override
    protected String expectedToStringOfSimple() {
        return expectedDescriptionOfSimple();
    }

    @Override
    protected void assertSimpleOutput(List<Page> input, List<Page> results) {
        final int positions = input.stream().map(page -> page.<Block>getBlock(0)).mapToInt(Block::getPositionCount).sum();
        final int expectedValue = positions;
        final int resultChannel = 2;
        for (var page : results) {
            LongBlock lb = page.getBlock(resultChannel);
            IntStream.range(0, lb.getPositionCount()).forEach(pos -> assertEquals(expectedValue, lb.getLong(pos)));
        }
    }

    public void testReadFromBlock() {
        DriverContext context = driverContext();
        List<Page> input = CannedSourceOperator.collectPages(simpleInput(context.blockFactory(), 10));
        List<Page> results = drive(new EvalOperator.EvalOperatorFactory(dvrCtx -> new LoadFromPage(0)).get(context), input.iterator());
        Set<Long> found = new TreeSet<>();
        for (var page : results) {
            LongBlock lb = page.getBlock(2);
            IntStream.range(0, lb.getPositionCount()).forEach(pos -> found.add(lb.getLong(pos)));
        }
        assertThat(found, equalTo(LongStream.range(0, 10).mapToObj(Long::valueOf).collect(Collectors.toSet())));
        results.forEach(Page::releaseBlocks);
        assertThat(context.breaker().getUsed(), equalTo(0L));
    }

    @Override
    protected ByteSizeValue smallEnoughToCircuitBreak() {
        return ByteSizeValue.ofBytes(between(1, 8000));
    }

    @Override
    protected DriverContext driverContext() { // TODO remove this when the parent uses a breaking block factory
        return breakingDriverContext();
    }
}
