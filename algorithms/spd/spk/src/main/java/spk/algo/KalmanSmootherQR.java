/**
 * Kalman Smoother (explicit Q and R)
 *
 * Forward (causal) Kalman filter with a local-linear-trend state model. Same
 * math as KalmanSmoother but with the process-noise spectral density and the
 * measurement-noise variance exposed as separate, physically-meaningful
 * tuning parameters instead of being collapsed into a single Q/R ratio.
 */
package spk.algo;

import java.util.Date;

import ilex.var.NamedVariable;
import decodes.tsdb.DbCompException;
import decodes.tsdb.algo.AWAlgoType;

//AW:IMPORTS
import java.util.ArrayList;
//AW:IMPORTS_END

//AW:JAVADOC
/**
 * Forward Kalman filter for a single timeseries using a local-linear-trend
 * (level + slope) state model with explicit Q and R tuning.
 *
 * <ul>
 *   <li>{@code processNoise} (Q spectral density) — random-acceleration noise
 *       in (units^2 / sec^3). Controls how quickly the trend is allowed to
 *       change. Larger = filter follows real changes faster but admits more
 *       observation noise.</li>
 *   <li>{@code measurementNoise} (R) — observation variance, in units^2. Set
 *       this to your best estimate of the squared sensor noise. Larger =
 *       each observation is trusted less, output is smoother.</li>
 * </ul>
 *
 * Causal: the estimate at time t depends only on observations up to t.
 */
//AW:JAVADOC_END
public class KalmanSmootherQR
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
//AW:LOCALVARS_END

//AW:OUTPUTS
	public NamedVariable smooth = new NamedVariable("smooth", 0);
	String _outputNames[] = { "smooth" };
//AW:OUTPUTS_END

//AW:PROPERTIES
	public double processNoise = 1.0e-6;
	public double measurementNoise = 1.0;
	public double initialVariance = 1.0e6;
	public long   minSamplesNeeded = 4;
	public long   maxSamples = 0;	// 0 = unlimited; otherwise only the most recent N samples are filtered
	public String _propertyNames[] = {
		"processNoise", "measurementNoise", "initialVariance", "minSamplesNeeded", "maxSamples"
	};
//AW:PROPERTIES_END

	protected void initAWAlgorithm( )
		throws DbCompException
	{
//AW:INIT
		_awAlgoType = AWAlgoType.RUNNING_AGGREGATE;
		_aggPeriodVarRoleName = "smooth";
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
	{
//AW:AFTER_TIMESLICES
		try
		{
			if (indepValues.size() < minSamplesNeeded)
			{
				debug1("Insufficient valid data for this timeslice");
				return;
			}

			final double r = measurementNoise;
			final double qa = processNoise;

			int from = 0;
			if (maxSamples > 0 && indepValues.size() > maxSamples)
			{
				from = indepValues.size() - (int) maxSamples;
			}

			// State = [level]; covariance = p
			double level = indepValues.get(from);
			double p = initialVariance;

			// Update with first sample
			{
				double s = p + r;
				double k = p / s;
				level += k * (indepValues.get(from) - level);
				p = (1.0 - k) * p;
			}

			for (int i = from + 1; i < indepValues.size(); i++)
			{
				double dt = (indepTimes.get(i) - indepTimes.get(i - 1)) / 1000.0;
				if (dt <= 0.0)
				{
					continue;
				}

				// Predict: level unchanged, variance grows linearly with dt
				p = p + qa * dt;

				// Update
				double s = p + r;
				double k = p / s;
				level += k * (indepValues.get(i) - level);
				p = (1.0 - k) * p;
			}

			if (!_aggregatePeriodEnd.after(lastTimeSlice))
			{
				setOutput(smooth, level);
			}
		}
		catch (Exception e)
		{
			warning(e.getLocalizedMessage());
			throw e;
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
