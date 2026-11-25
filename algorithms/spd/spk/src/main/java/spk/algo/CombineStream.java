package spk.algo;

import java.util.Date;

import ilex.var.NamedVariableList;
import ilex.var.NamedVariable;
import decodes.tsdb.DbAlgorithmExecutive;
import decodes.tsdb.DbCompException;
import decodes.tsdb.DbIoException;
import decodes.tsdb.VarFlags;
import decodes.tsdb.algo.AWAlgoType;

//AW:IMPORTS
import decodes.cwms.CwmsFlags;
import java.lang.Math;
import decodes.tsdb.ParmRef;
//AW:IMPORTS_END

//AW:JAVADOC
/**
 * Combine multiple generic streams into a single version
 * @author l2edhdto
 */
//AW:JAVADOC_END
public class CombineStream
	extends decodes.tsdb.algo.AW_AlgorithmBase
{
//AW:INPUTS
	public double input1;	//AW:TYPECODE=i
	public double input2;	//AW:TYPECODE=i
	public double input3;   //AW:TYPECODE=i
	String _inputNames[] = { "input1", "input2", "input3" };
//AW:INPUTS_END

//AW:LOCALVARS

//AW:LOCALVARS_END

//AW:OUTPUTS
	public NamedVariable output = new NamedVariable("output", 0);
	String _outputNames[] = { "output" };
//AW:OUTPUTS_END

//AW:PROPERTIES
	String _propertyNames[] = {  };
//AW:PROPERTIES_END

	// Allow javac to generate a no-args constructor.

	/**
	 * Algorithm-specific initialization provided by the subclass.
	 */
	protected void initAWAlgorithm( )
		throws DbCompException
	{
//AW:INIT
		_awAlgoType = AWAlgoType.TIME_SLICE;
//AW:INIT_END

//AW:USERINIT
//AW:USERINIT_END
	}
	
	/**
	 * This method is called once before iterating all time slices.
	 */
	protected void beforeTimeSlices()
		throws DbCompException
	{
//AW:BEFORE_TIMESLICES
		String units_i1 = null;
		String units_i2 = null;
		String units_i3 = null;
		String units_o = null;
		units_i1 = getInputUnitsAbbr( "input1" );
		units_i2 = getInputUnitsAbbr( "input2" );
		units_i3 = getInputUnitsAbbr( "input3" );
		units_o = getParmRef( "output").timeSeries.getUnitsAbbr();
		//units_i1 = ref1.timeSeries.getUnitsAbbr();
		//units_i2 = ref2.timeSeries.getUnitsAbbr();

		debug3( "time series units are:" );
		debug3( "  input 1: " + units_i1 );
		debug3( "  input 2: " + units_i2 );
		debug3( "  input 3: " + units_i3 );
		debug3( "  output:  " + units_o );
		debug3( "setting output TS units to input units" );
		setOutputUnitsAbbr("output", units_i1);



//AW:BEFORE_TIMESLICES_END
	}

	/**
	 * Do the algorithm for a single time slice.
	 * AW will fill in user-supplied code here.
	 * Base class will set inputs prior to calling this method.
	 * User code should call one of the setOutput methods for a time-slice
	 * output variable.
	 *
	 * @throws DbCompException (or subclass thereof) if execution of this
	 *        algorithm is to be aborted.
	 */
	protected void doAWTimeSlice()
		throws DbCompException
	{
//AW:TIMESLICE
		debug3( "inputs are:");
		debug3( " input1 = " + input1 );
		debug3( " input2 = " + input2 );
		debug3( " input3 = " + input3 );
		if( !isMissing( input1 ) )
		{
			debug3( "Using First Input" );
			setOutput( output, input1 );
		}
		else if( !isMissing( input2 ) )
		{
			debug3( "Using Second Input" );
			setOutput( output, input2 );
		}
		else if( !isMissing( input3))
		{
			debug3( "Using Third Input" );
			setOutput( output, input3 );
		}
		else
		{
			debug2( "Using no input, all inputs are missing" );
			deleteOutput( output );
		}
//AW:TIMESLICE_END
	}

	/**
	 * This method is called once after iterating all time slices.
	 */
	protected void afterTimeSlices()
		throws DbCompException
	{
//AW:AFTER_TIMESLICES
//AW:AFTER_TIMESLICES_END
	}

	/**
	 * Required method returns a list of all input time series names.
	 */
	public String[] getInputNames()
	{
		return _inputNames;
	}

	/**
	 * Required method returns a list of all output time series names.
	 */
	public String[] getOutputNames()
	{
		return _outputNames;
	}

	/**
	 * Required method returns a list of properties that have meaning to
	 * this algorithm.
	 */
	public String[] getPropertyNames()
	{
		return _propertyNames;
	}
}
