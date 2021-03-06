/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.dataflow.sdk.runners.inprocess;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.cloud.dataflow.sdk.runners.inprocess.InMemoryWatermarkManager.TimerUpdate;
import com.google.cloud.dataflow.sdk.runners.inprocess.InProcessExecutionContext.InProcessStepContext;
import com.google.cloud.dataflow.sdk.runners.inprocess.InProcessPipelineRunner.CommittedBundle;
import com.google.cloud.dataflow.sdk.runners.inprocess.InProcessPipelineRunner.UncommittedBundle;
import com.google.cloud.dataflow.sdk.testing.TestPipeline;
import com.google.cloud.dataflow.sdk.transforms.AppliedPTransform;
import com.google.cloud.dataflow.sdk.transforms.Create;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.ParDo;
import com.google.cloud.dataflow.sdk.transforms.View;
import com.google.cloud.dataflow.sdk.transforms.windowing.BoundedWindow;
import com.google.cloud.dataflow.sdk.transforms.windowing.GlobalWindow;
import com.google.cloud.dataflow.sdk.transforms.windowing.IntervalWindow;
import com.google.cloud.dataflow.sdk.transforms.windowing.PaneInfo;
import com.google.cloud.dataflow.sdk.transforms.windowing.Window;
import com.google.cloud.dataflow.sdk.util.IdentitySideInputWindowFn;
import com.google.cloud.dataflow.sdk.util.ReadyCheckingSideInputReader;
import com.google.cloud.dataflow.sdk.util.WindowedValue;
import com.google.cloud.dataflow.sdk.util.common.CounterSet;
import com.google.cloud.dataflow.sdk.util.common.worker.StateSampler;
import com.google.cloud.dataflow.sdk.values.PCollection;
import com.google.cloud.dataflow.sdk.values.PCollectionView;
import com.google.cloud.dataflow.sdk.values.TupleTag;
import com.google.cloud.dataflow.sdk.values.TupleTagList;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import org.hamcrest.Matchers;
import org.joda.time.Instant;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Tests for {@link ParDoInProcessEvaluator}.
 */
@RunWith(JUnit4.class)
public class ParDoInProcessEvaluatorTest {
  @Mock private InProcessEvaluationContext evaluationContext;
  private PCollection<Integer> inputPc;
  private TupleTag<Integer> mainOutputTag;
  private List<TupleTag<?>> sideOutputTags;
  private BundleFactory bundleFactory;

  @Before
  public void setup() {
    MockitoAnnotations.initMocks(this);
    TestPipeline p = TestPipeline.create();
    inputPc = p.apply(Create.of(1, 2, 3));
    mainOutputTag = new TupleTag<Integer>() {};
    sideOutputTags = TupleTagList.empty().getAll();

    bundleFactory = InProcessBundleFactory.create();
  }

  @Test
  public void sideInputsNotReadyResultHasUnprocessedElements() {
    PCollectionView<Integer> singletonView =
        inputPc
            .apply(Window.into(new IdentitySideInputWindowFn()))
            .apply(View.<Integer>asSingleton().withDefaultValue(0));
    RecorderFn fn = new RecorderFn(singletonView);
    PCollection<Integer> output = inputPc.apply(ParDo.of(fn).withSideInputs(singletonView));

    CommittedBundle<Integer> inputBundle =
        bundleFactory.createRootBundle(inputPc).commit(Instant.now());
    UncommittedBundle<Integer> outputBundle = bundleFactory.createBundle(inputBundle, output);
    when(evaluationContext.createBundle(inputBundle, output))
        .thenReturn(outputBundle);

    ParDoInProcessEvaluator<Integer> evaluator =
        createEvaluator(singletonView, fn, inputBundle, output);

    IntervalWindow nonGlobalWindow = new IntervalWindow(new Instant(0), new Instant(10_000L));
    WindowedValue<Integer> first = WindowedValue.valueInGlobalWindow(3);
    WindowedValue<Integer> second =
        WindowedValue.of(2, new Instant(1234L), nonGlobalWindow, PaneInfo.NO_FIRING);
    WindowedValue<Integer> third =
        WindowedValue.of(
            1,
            new Instant(2468L),
            ImmutableList.of(nonGlobalWindow, GlobalWindow.INSTANCE),
            PaneInfo.NO_FIRING);

    evaluator.processElement(first);
    evaluator.processElement(second);
    evaluator.processElement(third);
    InProcessTransformResult result = evaluator.finishBundle();

    assertThat(
        result.getUnprocessedElements(),
        Matchers.<WindowedValue<?>>containsInAnyOrder(
            second, WindowedValue.of(1, new Instant(2468L), nonGlobalWindow, PaneInfo.NO_FIRING)));
    assertThat(result.getOutputBundles(), Matchers.<UncommittedBundle<?>>contains(outputBundle));
    assertThat(RecorderFn.processed, containsInAnyOrder(1, 3));
    assertThat(
        Iterables.getOnlyElement(result.getOutputBundles()).commit(Instant.now()).getElements(),
        Matchers.<WindowedValue<?>>containsInAnyOrder(
            first.withValue(8),
            WindowedValue.timestampedValueInGlobalWindow(6, new Instant(2468L))));
  }

  private ParDoInProcessEvaluator<Integer> createEvaluator(
      PCollectionView<Integer> singletonView,
      RecorderFn fn,
      InProcessPipelineRunner.CommittedBundle<Integer> inputBundle,
      PCollection<Integer> output) {
    when(
            evaluationContext.createSideInputReader(
                ImmutableList.<PCollectionView<?>>of(singletonView)))
        .thenReturn(new ReadyInGlobalWindowReader());
    InProcessExecutionContext executionContext = mock(InProcessExecutionContext.class);
    InProcessStepContext stepContext = mock(InProcessStepContext.class);
    when(executionContext.getOrCreateStepContext(Mockito.any(String.class),
        Mockito.any(String.class),
        Mockito.any(StateSampler.class))).thenReturn(stepContext);
    when(stepContext.getTimerUpdate()).thenReturn(TimerUpdate.empty());
    when(
            evaluationContext.getExecutionContext(
                Mockito.any(AppliedPTransform.class), Mockito.any(Object.class)))
        .thenReturn(executionContext);
    when(evaluationContext.createCounterSet()).thenReturn(new CounterSet());

    return ParDoInProcessEvaluator.create(
        evaluationContext,
        inputBundle,
        (AppliedPTransform<PCollection<Integer>, ?, ?>) output.getProducingTransformInternal(),
        fn,
        ImmutableList.<PCollectionView<?>>of(singletonView),
        mainOutputTag,
        sideOutputTags,
        ImmutableMap.<TupleTag<?>, PCollection<?>>of(mainOutputTag, output));
  }

  private static class RecorderFn extends DoFn<Integer, Integer> {
    private static Collection<Integer> processed;
    private final PCollectionView<Integer> view;

    public RecorderFn(PCollectionView<Integer> view) {
      processed = new ArrayList<>();
      this.view = view;
    }

    @Override
    public void processElement(DoFn<Integer, Integer>.ProcessContext c) throws Exception {
      processed.add(c.element());
      c.output(c.element() + c.sideInput(view));
    }
  }

  private static class ReadyInGlobalWindowReader implements ReadyCheckingSideInputReader {
    @Override
    @Nullable
    public <T> T get(PCollectionView<T> view, BoundedWindow window) {
      if (window.equals(GlobalWindow.INSTANCE)) {
        return (T) (Integer) 5;
      }
      fail("Should only call get in the Global Window, others are not ready");
      throw new AssertionError("Unreachable");
    }

    @Override
    public <T> boolean contains(PCollectionView<T> view) {
      return true;
    }

    @Override
    public boolean isEmpty() {
      return false;
    }

    @Override
    public boolean isReady(PCollectionView<?> view, BoundedWindow window) {
      return window.equals(GlobalWindow.INSTANCE);
    }
  }
}
