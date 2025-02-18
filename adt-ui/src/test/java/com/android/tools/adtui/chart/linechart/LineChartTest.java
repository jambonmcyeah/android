/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.adtui.chart.linechart;

import com.android.tools.adtui.model.*;
import org.jetbrains.annotations.NotNull;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static java.awt.BasicStroke.CAP_SQUARE;
import static java.awt.BasicStroke.JOIN_MITER;
import static org.mockito.Mockito.*;

public class LineChartTest {

  @Test
  public void testNoRenderWithEmptyRange() {
    // Ensures that if the LineChartModel hasn't had a chance to update and the yRange remains zero - then the LineChart would not render
    // any data.
    LineChartModel model = new LineChartModel();
    Range xRange = new Range(0, 10);
    Range yRange = new Range(0, 0);
    DefaultDataSeries<Long> testSeries = new DefaultDataSeries<>();
    for (int i = 0; i < 11; i++) {
      testSeries.add(i, (long)i);
    }
    RangedContinuousSeries rangedSeries = new RangedContinuousSeries("test", xRange, yRange, testSeries);
    model.add(rangedSeries);

    LineChart chart = new LineChart(model);
    chart.setSize(100, 100);
    Graphics2D fakeGraphics = mock(Graphics2D.class);
    when(fakeGraphics.create()).thenReturn(fakeGraphics);
    doThrow(new AssertionError()).when(fakeGraphics).draw(any(Shape.class));
    chart.paint(fakeGraphics);
  }

  @Test
  public void testRenderConfigNoWithData() {
    LineChartModel model = new LineChartModel();
    DefaultDataSeries<Long> emptySeries = new DefaultDataSeries<>();
    DefaultDataSeries<Long> seriesWithData = new DefaultDataSeries<>();
    for (int i = 0; i < 11; i++) {
      seriesWithData.add(i, (long)i);
    }
    RangedContinuousSeries rangedEmptySeries = new RangedContinuousSeries("emptySeries", new Range(), new Range(), emptySeries);
    RangedContinuousSeries rangedSeriesWithData =
      new RangedContinuousSeries("seriesWithData", new Range(0, 10), new Range(0, 0), seriesWithData);
    model.add(rangedEmptySeries);
    model.add(rangedSeriesWithData);

    LineChart chart = new LineChart(model);
    chart.configure(rangedEmptySeries, new LineConfig(Color.BLACK));
    chart.configure(rangedSeriesWithData, new LineConfig(Color.WHITE));
    model.update(TimeUnit.SECONDS.toNanos(1));
    chart.setSize(100, 100);
    Graphics2D fakeGraphics = mock(Graphics2D.class);
    when(fakeGraphics.create()).thenReturn(fakeGraphics);
    chart.paint(fakeGraphics);

    // A line should be drawn for the seriesWithData, but nothing for the emptySeries.
    verify(fakeGraphics, times(1)).draw(any(Shape.class));
  }

  @Test
  public void testAdjustDashPhase() {
    LineChartModel model = new LineChartModel();
    Range xRange = new Range(0, 15);
    Range yRange = new Range(0, 15);

    DefaultDataSeries<Long> testSeries = new DefaultDataSeries<>();
    testSeries.add(3, 0L);
    testSeries.add(3, 3L);  // hypotenuse relative to previous point = 3
    testSeries.add(6, 7L);  // hypotenuse relative to previous point = 5
    testSeries.add(9, 11L);  // hypotenuse relative to previous point = 5
    testSeries.add(15, 15L);

    RangedContinuousSeries rangedSeries = new RangedContinuousSeries("test", xRange, yRange, testSeries);
    model.add(rangedSeries);

    LineChart chart = new LineChart(model);
    // Set dimension to match the ranges, so each range unit is 1 pixel.
    chart.setSize(15, 15);

    // Create a dash pattern of total length 3
    BasicStroke stroke = new BasicStroke(1f, CAP_SQUARE, JOIN_MITER, 10.0f, new float[]{1.0f, 2.0f}, 0.0f);
    LineConfig config = new LineConfig(Color.BLACK).setStroke(stroke);

    chart.configure(rangedSeries, config);
    Assert.assertTrue(config.isAdjustDash());
    Assert.assertEquals(3, config.getDashLength(), 0);
    Assert.assertEquals(0, config.getAdjustedDashPhase(), 0);

    // First point == {3,0}
    Graphics2D fakeGraphics = mock(Graphics2D.class);
    when(fakeGraphics.create()).thenReturn(fakeGraphics);
    shiftRangeAndRepaintChart(chart, model, xRange, fakeGraphics, 0);
    Assert.assertEquals(0, config.getAdjustedDashPhase(), LineChart.EPSILON);

    // The new first point would be same as last. Dash phase should not have changed.
    shiftRangeAndRepaintChart(chart, model, xRange, fakeGraphics, 4);
    Assert.assertEquals(0, config.getAdjustedDashPhase(), LineChart.EPSILON);

    // The new first point would now be {6,7}, which is 8 pixels ahead relative to the previous first point {3,0}
    // New dash phase would be: (0 + 8) % 3
    shiftRangeAndRepaintChart(chart, model, xRange, fakeGraphics, 4);
    Assert.assertEquals(2, config.getAdjustedDashPhase(), LineChart.EPSILON);

    // The new first point would now be {9,11}, which is another 5 pixels ahead of the previous point
    // New dash phase would be: (2 + 5) % 3
    shiftRangeAndRepaintChart(chart, model, xRange, fakeGraphics, 4);
    Assert.assertEquals(1, config.getAdjustedDashPhase(), LineChart.EPSILON);

    // Shifts backward to ensure we get back the previous dash phases.
    shiftRangeAndRepaintChart(chart, model, xRange, fakeGraphics, -4);
    Assert.assertEquals(2, config.getAdjustedDashPhase(), LineChart.EPSILON);
    shiftRangeAndRepaintChart(chart, model, xRange, fakeGraphics, -4);
    Assert.assertEquals(0, config.getAdjustedDashPhase(), LineChart.EPSILON);
    shiftRangeAndRepaintChart(chart, model, xRange, fakeGraphics, -4);
    Assert.assertEquals(0, config.getAdjustedDashPhase(), LineChart.EPSILON);

    // Test that shifting by more than half would not update the dash phase.
    shiftRangeAndRepaintChart(chart, model, xRange, fakeGraphics, 8);
    Assert.assertEquals(0, config.getAdjustedDashPhase(), LineChart.EPSILON);
    shiftRangeAndRepaintChart(chart, model, xRange, fakeGraphics, -8);
    Assert.assertEquals(0, config.getAdjustedDashPhase(), LineChart.EPSILON);
  }

  @Test
  public void testAdjustDashPhaseForSteppedConfig() throws Exception {
    LineChartModel model = new LineChartModel();
    Range xRange = new Range(0, 15);
    Range yRange = new Range(0, 15);

    DefaultDataSeries<Long> testSeries = new DefaultDataSeries<>();
    testSeries.add(3, 0L);
    testSeries.add(3, 3L);  // stepped length relative to previous point = 3
    testSeries.add(6, 7L);  // stepped length relative to previous point = 7
    testSeries.add(9, 11L);  // stepped length relative to previous point = 7
    testSeries.add(15, 15L);

    RangedContinuousSeries rangedSeries = new RangedContinuousSeries("test", xRange, yRange, testSeries);
    model.add(rangedSeries);

    LineChart chart = new LineChart(model);
    // Set dimension to match the ranges, so each range unit is 1 pixel.
    chart.setSize(15, 15);

    // Create a dash pattern of total length 3
    BasicStroke stroke = new BasicStroke(1f, CAP_SQUARE, JOIN_MITER, 10.0f, new float[]{1.0f, 2.0f}, 0.0f);
    LineConfig config = new LineConfig(Color.BLACK).setStroke(stroke).setStepped(true);

    chart.configure(rangedSeries, config);
    Assert.assertTrue(config.isAdjustDash());
    Assert.assertEquals(3, config.getDashLength(), 0);
    Assert.assertEquals(0, config.getAdjustedDashPhase(), 0);

    // First point == {3,0}
    Graphics2D fakeGraphics = mock(Graphics2D.class);
    when(fakeGraphics.create()).thenReturn(fakeGraphics);
    shiftRangeAndRepaintChart(chart, model, xRange, fakeGraphics, 0);
    Assert.assertEquals(0, config.getAdjustedDashPhase(), LineChart.EPSILON);

    // The new first point would be same as last. Dash phase should not have changed.
    shiftRangeAndRepaintChart(chart, model, xRange, fakeGraphics, 4);
    Assert.assertEquals(0, config.getAdjustedDashPhase(), LineChart.EPSILON);

    // The new first point would now be {6,7}, which for a stepped line is 10 pixels ahead relative to the previous first point {3,0}
    // New dash phase would be: (0 + 10) % 3
    shiftRangeAndRepaintChart(chart, model, xRange, fakeGraphics, 4);
    Assert.assertEquals(1, config.getAdjustedDashPhase(), LineChart.EPSILON);

    // The new first point would now be {9,11}, which for a stepped line is another 7 pixels ahead of the previous point
    // New dash phase would be: (1 + 7) % 3
    shiftRangeAndRepaintChart(chart, model, xRange, fakeGraphics, 4);
    Assert.assertEquals(2, config.getAdjustedDashPhase(), LineChart.EPSILON);

    // Shifts backward to ensure we get back the previous dash phases.
    shiftRangeAndRepaintChart(chart, model, xRange, fakeGraphics, -4);
    Assert.assertEquals(1, config.getAdjustedDashPhase(), LineChart.EPSILON);
    shiftRangeAndRepaintChart(chart, model, xRange, fakeGraphics, -4);
    Assert.assertEquals(0, config.getAdjustedDashPhase(), LineChart.EPSILON);
    shiftRangeAndRepaintChart(chart, model, xRange, fakeGraphics, -4);
    Assert.assertEquals(0, config.getAdjustedDashPhase(), LineChart.EPSILON);

    // Test that shifting by more than half would not update the dash phase.
    shiftRangeAndRepaintChart(chart, model, xRange, fakeGraphics, 8);
    Assert.assertEquals(0, config.getAdjustedDashPhase(), LineChart.EPSILON);
    shiftRangeAndRepaintChart(chart, model, xRange, fakeGraphics, -8);
    Assert.assertEquals(0, config.getAdjustedDashPhase(), LineChart.EPSILON);
  }

  @Test
  public void testConfigLerpsMinMaxValues() {
    LineChartModel model = new LineChartModel();
    Range xRange = new Range(8, 12);
    Range yRange = new Range(0, 10);
    int windowHeight = 20;

    DefaultDataSeries<Long> testSeries = new ReturnAllDataSeries();
    testSeries.add(0, 0L);
    testSeries.add(4, 2L);
    testSeries.add(6, 4L);
    testSeries.add(10, 6L);
    testSeries.add(14, 8L);
    testSeries.add(20, 10L);

    RangedContinuousSeries rangedSeries = new RangedContinuousSeries("test", xRange, yRange, testSeries);
    model.add(rangedSeries);

    float[][] expectedPoints = {
      // The first point is the 6 point. Points before this are dropped.
      {0.0f, computeYValue(4, 6, .5f, yRange, windowHeight)},
      // The next point is going from 6 -> 10
      {2.0f, computeYValue(6, 8, 0.0f, yRange, windowHeight)},
      // The next point is going from 10 -> 14
      {4.0f, computeYValue(6, 8, 0.5f, yRange, windowHeight)},
      // The final points get dropped, so should be 0 to indicate the end.
      {0.0f, 0.0f}
    };

    // Configure Chart.
    LineChart chart = new LineChart(model);
    chart.setSize(4, windowHeight);
    BasicStroke stroke = new BasicStroke(1f);
    LineConfig config = new LineConfig(Color.BLACK).setStroke(stroke).setStepped(false);
    chart.configure(rangedSeries, config);

    // Configure Mocks.
    Graphics2D fakeGraphics = mock(Graphics2D.class);
    ArgumentCaptor valueCapture = ArgumentCaptor.forClass(Shape.class);
    when(fakeGraphics.create()).thenReturn(fakeGraphics);
    doNothing().when(fakeGraphics).draw((Shape)valueCapture.capture());

    // Update and draw chart.
    shiftRangeAndRepaintChart(chart, model, xRange, fakeGraphics, 0);
    java.util.List<Path2D.Float> values = valueCapture.getAllValues();
    Assert.assertEquals(values.size(), 1);

    // Validate each point
    PathIterator it = values.get(0).getPathIterator(null);
    for (int i = 0; !it.isDone() && i < expectedPoints.length; i++) {
      float[] coords = new float[2];
      it.currentSegment(coords);
      Assert.assertArrayEquals(expectedPoints[i], coords, 0.000001f);
      it.next();
    }
  }

  /**
   * Helper function to convert from series data to expected test value.
   * @param previousY the series value expected from a previous point.
   * @param nextY the series value expected on the current or next point.
   * @param ratio the ratio between the two values used.
   * @param range the range used for normalizing the y values between 0 and 1.
   * @param windowHeight the scaler used to scale the y points back to the window size.
   * @return the pixel location of two Y values interpolated between some ratio.
   */
  private float computeYValue(float previousY, float nextY, float ratio, Range range, int windowHeight) {
    double ydPrev = 1 - (previousY - range.getMin()) / range.getLength();
    double ydNext = 1 - (nextY - range.getMin()) / range.getLength();
    return (float)((((1 - ratio) * ydPrev) + (ratio * ydNext)) * windowHeight);
  }

  private void shiftRangeAndRepaintChart(@NotNull LineChart chart,
                                         @NotNull LineChartModel model,
                                         @NotNull Range range,
                                         @NotNull Graphics graphics,
                                         double delta) {
    range.shift(delta);
    model.update(FakeTimer.ONE_SECOND_IN_NS);
    chart.paint(graphics);
  }

  private static final class ReturnAllDataSeries extends DefaultDataSeries<Long> {
    @Override
    public List<SeriesData<Long>> getDataForXRange(Range xRange) {
      return getAllData();
    }
  }
}