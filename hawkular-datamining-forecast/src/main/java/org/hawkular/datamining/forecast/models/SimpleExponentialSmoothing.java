/*
 * Copyright 2015-2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.hawkular.datamining.forecast.models;

import java.util.List;

import org.apache.commons.math3.analysis.MultivariateFunction;
import org.apache.commons.math3.optim.nonlinear.scalar.MultivariateFunctionMappingAdapter;
import org.hawkular.datamining.forecast.DataPoint;
import org.hawkular.datamining.forecast.ImmutableMetricContext;
import org.hawkular.datamining.forecast.Logger;
import org.hawkular.datamining.forecast.MetricContext;
import org.hawkular.datamining.forecast.stats.AccuracyStatistics;

/**
 * Simple exponential smoothing
 * Works well for stationary data when there is no trend in data. For trending data forecasts produce high MSE due to
 * flat forecast function.
 *
 * <p>
 * When smoothing parameters are smaller more weights are added to the observations from distant past - it makes it
 * model more robust.
 *
 * <p>
 * Equations:
 * <ul>
 *  <li> level<sub>t</sub> = alpha*y<sub>t</sub> + (1-alpha)level<sub>t-1</sub> </li>
 *  <li> forecast<sub>t+h</sub> = level<sub>t</sub> </li>
 * </ul>
 *
 * @author Pavol Loffay
 */
public class SimpleExponentialSmoothing extends AbstractExponentialSmoothing {

    public static final double DEFAULT_LEVEL_SMOOTHING = 0.4;
    public static final double MIN_LEVEL_SMOOTHING = 0.0001;
    public static final double MAX_LEVEL_SMOOTHING = 0.9999;

    private final double levelSmoothing;

    private State state;

    public static class State {

        protected double level;

        public State(double level) {
            this.level = level;
        }
    }


    public SimpleExponentialSmoothing() {
        this(DEFAULT_LEVEL_SMOOTHING, ImmutableMetricContext.getDefault());
    }

    public SimpleExponentialSmoothing(double levelSmoothing) {
        this(DEFAULT_LEVEL_SMOOTHING, ImmutableMetricContext.getDefault());
    }

    public SimpleExponentialSmoothing(double levelSmoothing, MetricContext metricContext) {
        super(metricContext);

        if (levelSmoothing < MIN_LEVEL_SMOOTHING || levelSmoothing > MAX_LEVEL_SMOOTHING) {
            throw new IllegalArgumentException("Level parameter should be in interval 0-1");
        }
        this.levelSmoothing = levelSmoothing;
    }

    @Override
    public String name() {
        return "Simple exponential smoothing";
    }

    @Override
    public int numberOfParams() {
        return 2;
    }

    @Override
    public int minimumInitSize() {
        return 0;
    }

    @Override
    protected State initState(List<DataPoint> initData) {

        if (initData.isEmpty()) {
            throw new IllegalArgumentException("For init is required at least one point");
        }

        double level;

        if (initData.size() == 1) {
            level = initData.get(0).getValue();
        } else {
            // mean
            Double sum = initData.stream().map(dataPoint -> dataPoint.getValue())
                    .reduce((aDouble, aDouble2) -> aDouble + aDouble2).get();
            level = sum / (double) initData.size();
        }

        state = new State(level);
        return state;
    }

    @Override
    protected State state() {
        return state;
    }

    @Override
    protected void updateState(DataPoint dataPoint) {
        state.level = levelSmoothing*dataPoint.getValue() + (1 - levelSmoothing)*state.level;
    }

    @Override
    protected double calculatePrediction(long nAhead, Long learnTimestamp) {
        return state.level;
    }

    public static Optimizer optimizer() {
        return new Optimizer(new ImmutableMetricContext(null, null, 1L));
    }

    public static Optimizer optimizer(MetricContext metricContext) {
        return new Optimizer(metricContext);
    }

    @Override
    public String toString() {
        return "SimpleExponentialSmoothing{" +
                "alpha=" + levelSmoothing +
                ", level=" + state.level +
                '}';
    }

    public static class Optimizer extends AbstractModelOptimizer {


        public Optimizer(MetricContext metricContext) {
            super(metricContext);
        }

        @Override
        public TimeSeriesModel minimizedMSE(List<DataPoint> dataPoints) {
            if (dataPoints.isEmpty()) {
                return new SimpleExponentialSmoothing();
            }

            optimize(new double[]{DEFAULT_LEVEL_SMOOTHING}, costFunction(dataPoints));
            Logger.LOGGER.debugf("Simple ES: Optimizer best alpha: %f", result[0]);

            SimpleExponentialSmoothing bestModel = new SimpleExponentialSmoothing(result[0], getMetricContext());
            bestModel.init(dataPoints);

            return bestModel;
        }

        public MultivariateFunctionMappingAdapter costFunction(final List<DataPoint> dataPoints) {
            // func for minimization
            MultivariateFunction multivariateFunction = point -> {

                double alpha = point[0];

                SimpleExponentialSmoothing doubleExponentialSmoothing = new SimpleExponentialSmoothing(alpha,
                        getMetricContext());
                AccuracyStatistics accuracyStatistics = doubleExponentialSmoothing.init(dataPoints);

                return accuracyStatistics.getMse();
            };
            MultivariateFunctionMappingAdapter multivariateFunctionMappingAdapter =
                    new MultivariateFunctionMappingAdapter(multivariateFunction,
                            new double[]{MIN_LEVEL_SMOOTHING}, new double[]{MAX_LEVEL_SMOOTHING});

            return multivariateFunctionMappingAdapter;
        }
    }
}
