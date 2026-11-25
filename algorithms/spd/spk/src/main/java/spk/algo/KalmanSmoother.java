/**
 * Kalman Smoother
 *
 * Forward (causal) Kalman filter with a local-linear-trend state model.
 * State = [level, slope]; observation = level + noise. Output is the filtered
 * level estimate at the end of the aggregation period.
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
 * (level + slope) state model. Process noise is the integrated random
 * acceleration form scaled by the property {@code q}, with measurement noise
 * fixed at 1 -- so {@code q} is effectively the Q/R ratio that controls how
 * smooth (small q) versus responsive (large q) the filter is.
 *
 * Causal: the estimate at time t depends only on observations up to t. The
 * value emitted at the end of the aggregation period is the filtered estimate
 * after processing every sample in the window.
 */
//AW:JAVADOC_END
public class KalmanSmoother
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
	public double q = 1e-6;
	public double initialVariance = 1.0e6;
	public long   minSamplesNeeded = 4;
	public long   maxSamples = 0;	// 0 = unlimited; otherwise only the most recent N samples are filtered
	public String _propertyNames[] = { "q", "initialVariance", "minSamplesNeeded", "maxSamples" };
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

			int from = 0;
			if (maxSamples > 0 && indepValues.size() > maxSamples)
			{
				from = indepValues.size() - (int) maxSamples;
			}

			// State [level, slope]; covariance P = [[p11, p12],[p12, p22]].
			double level = indepValues.get(from);
			double slope = 0.0;
			double p11 = initialVariance;
			double p12 = 0.0;
			double p22 = initialVariance;

			// Update step for the first sample with R=1.
			{
				double s = p11 + 1.0;
				double k1 = p11 / s;
				double k2 = p12 / s;
				double y = indepValues.get(from) - level;
				level += k1 * y;
				slope += k2 * y;
				double np11 = (1.0 - k1) * p11;
				double np12 = (1.0 - k1) * p12;
				double np22 = p22 - k2 * p12;
				p11 = np11; p12 = np12; p22 = np22;
			}

			for (int i = from + 1; i < indepValues.size(); i++)
			{
				double dt = (indepTimes.get(i) - indepTimes.get(i - 1)) / 1000.0;
				if (dt <= 0.0)
				{
					// Skip out-of-order or duplicate timestamps.
					continue;
				}

				// Predict: x' = F x, P' = F P F^T + Q
				level = level + dt * slope;
				// slope unchanged

				double dt2 = dt * dt;
				double dt3 = dt2 * dt;
				double dt4 = dt2 * dt2;

				double np11 = p11 + 2.0 * dt * p12 + dt2 * p22 + q * (dt4 / 4.0);
				double np12 = p12 + dt * p22 + q * (dt3 / 2.0);
				double np22 = p22 + q * dt2;
				p11 = np11; p12 = np12; p22 = np22;

				// Update with measurement z, H=[1,0], R=1.
				double s = p11 + 1.0;
				double k1 = p11 / s;
				double k2 = p12 / s;
				double y = indepValues.get(i) - level;
				level += k1 * y;
				slope += k2 * y;

				double up11 = (1.0 - k1) * p11;
				double up12 = (1.0 - k1) * p12;
				double up22 = p22 - k2 * p12;
				p11 = up11; p12 = up12; p22 = up22;
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
