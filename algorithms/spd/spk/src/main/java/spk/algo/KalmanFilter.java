/**
 * Kalman Filter
 *
 * Forward (causal) Kalman filter that maintains its state with explicit
 * Apache Commons Math matrices (F, H, Q, R, P, K). Equivalent in spirit to
 * KalmanSmoother / KalmanSmootherQR but written in matrix form so the model
 * can be inspected directly. Output is the filtered estimate at the end of
 * the aggregation period.
 */
package spk.algo;

import java.util.Date;

import ilex.var.NamedVariable;
import decodes.tsdb.DbCompException;
import decodes.tsdb.algo.AWAlgoType;

//AW:IMPORTS
import java.util.ArrayList;
import java.util.Collections;

import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;
//AW:IMPORTS_END

//AW:JAVADOC
/**
 * Matrix-form forward Kalman filter for a single timeseries using a
 * local-linear-trend (level + slope) state model. The transition matrix F,
 * observation matrix H, process-noise covariance Q and measurement-noise
 * covariance R are built up at init and then applied per timeslice through
 * standard predict / update steps.
 *
 * Causal: the estimate at time t depends only on observations up to t. The
 * value emitted at the end of the aggregation period is the filtered estimate
 * after processing every sample in the window.
 */
//AW:JAVADOC_END
public class KalmanFilter
	extends decodes.tsdb.algo.AW_AlgorithmBase
{
//AW:INPUTS
	public double rough;	//AW:TYPECODE=i
	String _inputNames[] = { "rough" };
//AW:INPUTS_END

//AW:LOCALVARS
	Date lastTimeSlice = null;
	ArrayList<Long> indepTimes = new ArrayList<Long>();
	ArrayList<Double> indepValues = new ArrayList<Double>();

	// State-space model matrices (raw arrays seeded at init from the properties).
	double[][] F;
	double[][] H = { { 1.0, 0.0 } };
	double[][] Q;
	double[][] R;
	double[][] B = { { 0.0 } };
	double[][] P = { { 1.0, 0.0 }, { 0.0, 1.0 } };

	// Working RealMatrix / RealVector forms used during predict / update.
	RealVector x;
	RealMatrix _b;
	RealMatrix _f;
	RealMatrix _h;
	RealMatrix _i;
	RealMatrix _k;
	RealMatrix _q;
	RealMatrix _r;
	RealMatrix _p;
	RealMatrix _s;
	RealMatrix _x;
//AW:LOCALVARS_END

//AW:OUTPUTS
	public NamedVariable smooth = new NamedVariable("smooth", 0);
	public NamedVariable prediction = new NamedVariable("prediction", 0);
	String _outputNames[] = { "smooth", "prediction" };
//AW:OUTPUTS_END

//AW:PROPERTIES
	public long minSamplesNeeded = 72;
	public double processNoiseCovariance = 3e-13;
	public double observationNoiseCovariance = 0.5;
	// medianWindow: width of an optional centered median pre-filter applied
	// to the input samples before the Kalman recursion. 0 (default) or 1
	// disables it; 3 or 5 are typical for outlier rejection on quantized data.
	public long medianWindow = 0;
	// useSlope: true (default) runs the matrix-form local-linear-trend filter
	// (level + slope state) -- responsive but can overshoot at sharp transitions.
	// false runs a 1-state local-level (random walk) filter -- monotonic
	// convergence, no overshoot, but processNoiseCovariance must be retuned
	// because the units differ: in slope mode it is the spectral density q with
	// per-step level injection q*dt^4/4; in local-level mode it is qa with
	// per-step level injection qa*dt. To match the smoothing of the current
	// q=3e-13 slope-mode tuning at dt=900s, use qa~5e-4 in local-level mode.
	public boolean useSlope = true;
	// slopeDamping: only meaningful when useSlope=true. The slope state's
	// transition becomes slope' = slopeDamping * slope each predict step.
	// 1.0 (default) is pure local-linear-trend (slope persists indefinitely
	// -- can produce overshoot at sharp transitions). Values below 1 implement
	// Holt's damped trend: slope decays toward zero each step, reducing
	// overshoot without losing ramp-tracking on sustained rises/falls. 0.85
	// is a good starting point for hourly-scale process data.
	public double slopeDamping = 1.0;
	public String _propertyNames[] = { "minSamplesNeeded", "processNoiseCovariance", "observationNoiseCovariance", "medianWindow", "useSlope", "slopeDamping" };
//AW:PROPERTIES_END

	protected double setInitialState(double x)
	{
		double[][] xMat = { { x, 0.0 }, { 0.0, 0.0 } };
		_x = MatrixUtils.createRealMatrix(xMat);
		_p = MatrixUtils.createRealMatrix(P);
		return x;
	}

	protected double predict(double dt)
	{
		double q = processNoiseCovariance;
		F = new double[][] { { 1.0, dt }, { 0.0, slopeDamping } };
		Q = new double[][] {
			{ q * dt * dt * dt * dt / 4.0, q * dt * dt * dt / 2.0 },
			{ q * dt * dt * dt / 2.0,      q * dt * dt           }
		};
		_f = MatrixUtils.createRealMatrix(F);
		_q = MatrixUtils.createRealMatrix(Q);

		_x = _f.multiply(_x);
		_p = _f.multiply(_p).multiply(_f.transpose()).add(_q);
		return _x.getEntry(0, 0);
	}

	protected void update(double z)
	{
		// Innovation: y = z - H x
		RealMatrix y = _h.multiply(_x).scalarMultiply(-1.0).scalarAdd(z);

		// Innovation covariance and Kalman gain.
		_s = _r.add(_h.multiply(_p.multiply(_h.transpose())));
		_k = _p.multiply(_h.transpose()).multiply(MatrixUtils.inverse(_s));

		// State update.
		_x = _x.add(_k.multiply(y));

		// Joseph-form covariance update for numerical stability.
		_i = MatrixUtils.createRealIdentityMatrix(2);
		RealMatrix tmp = _i.add(_k.multiply(_h).scalarMultiply(-1.0));
		_p = tmp.multiply(_p).multiply(tmp.transpose())
			.add(_k.multiply(_r).multiply(_k.transpose()));
	}

	protected void initAWAlgorithm()
		throws DbCompException
	{
//AW:INIT
		_awAlgoType = AWAlgoType.RUNNING_AGGREGATE;
		_aggPeriodVarRoleName = "smooth";

		R = new double[][] { { observationNoiseCovariance } };

		_h = MatrixUtils.createRealMatrix(H);
		_r = MatrixUtils.createRealMatrix(R);
		_p = MatrixUtils.createRealMatrix(P);
		_b = MatrixUtils.createRealMatrix(B);
		x = null;
//AW:INIT_END

//AW:USERINIT
//AW:USERINIT_END
	}

	protected void beforeTimeSlices()
		throws DbCompException
	{
//AW:BEFORE_TIMESLICES
		indepTimes.clear();
		indepValues.clear();
//AW:BEFORE_TIMESLICES_END
	}

	protected void doAWTimeSlice()
		throws DbCompException
	{
//AW:TIMESLICE
		if (!isMissing(rough))
		{
			indepTimes.add(_timeSliceBaseTime.getTime());
			indepValues.add(rough);
			lastTimeSlice = _timeSliceBaseTime;
		}
//AW:TIMESLICE_END
	}

	protected void afterTimeSlices()
		throws DbCompException
	{
//AW:AFTER_TIMESLICES
		if (indepValues.size() < minSamplesNeeded)
		{
			debug1("Insufficient valid data for this timeslice");
			return;
		}

		if (medianWindow > 1)
		{
			int w = (int) medianWindow;
			int half = w / 2;
			int n = indepValues.size();
			ArrayList<Double> filtered = new ArrayList<Double>(n);
			ArrayList<Double> window = new ArrayList<Double>(w);
			for (int i = 0; i < n; i++)
			{
				int lo = Math.max(0, i - half);
				int hi = Math.min(n - 1, i + half);
				window.clear();
				for (int j = lo; j <= hi; j++)
				{
					window.add(indepValues.get(j));
				}
				Collections.sort(window);
				filtered.add(window.get(window.size() / 2));
			}
			indepValues = filtered;
		}

		double levelOut;
		if (useSlope)
		{
			setInitialState(indepValues.get(0));
			long prevT = indepTimes.get(0);
			for (int i = 1; i < indepTimes.size(); i++)
			{
				long ti = indepTimes.get(i);
				double dt = (ti - prevT) / 1000.0;
				if (dt <= 0.0)
				{
					continue;
				}
				predict(dt);
				update(indepValues.get(i));
				prevT = ti;
			}
			levelOut = _x.getEntry(0, 0);
		}
		else
		{
			// Local-level (random walk) filter: state = [level], no slope state.
			// Monotonic convergence at any tuning, no slope-momentum overshoot.
			double level = indepValues.get(0);
			double p = 1.0e6;
			double r = observationNoiseCovariance;
			// No-op first update collapses initial variance toward p~r.
			{
				double s = p + r;
				double k = p / s;
				level += k * (indepValues.get(0) - level);
				p = (1.0 - k) * p;
			}
			long prevT = indepTimes.get(0);
			for (int i = 1; i < indepTimes.size(); i++)
			{
				long ti = indepTimes.get(i);
				double dt = (ti - prevT) / 1000.0;
				if (dt <= 0.0)
				{
					continue;
				}
				p = p + processNoiseCovariance * dt;
				double s = p + r;
				double k = p / s;
				level += k * (indepValues.get(i) - level);
				p = (1.0 - k) * p;
				prevT = ti;
			}
			levelOut = level;
		}

		debug1("_aggregatePeriodEnd: " + _aggregatePeriodEnd
			+ "; lastTimeSlice: " + lastTimeSlice);

		if (!_aggregatePeriodEnd.after(lastTimeSlice))
		{
			setOutput(smooth, levelOut);
		}
//AW:AFTER_TIMESLICES_END
	}

	public String[] getInputNames()
	{
		return _inputNames;
	}

	public String[] getOutputNames()
	{
		return _outputNames;
	}

	public String[] getPropertyNames()
	{
		return _propertyNames;
	}
}
