package mil.usace.army.swd.swt.algorithms;

import java.util.Date;

import ilex.var.NamedVariableList;
import ilex.var.NamedVariable;
import decodes.tsdb.DbAlgorithmExecutive;
import decodes.tsdb.DbCompException;
import decodes.tsdb.DbIoException;
import decodes.tsdb.VarFlags;
import decodes.tsdb.algo.AWAlgoType;
import decodes.tsdb.CTimeSeries;
import decodes.tsdb.ParmRef;
import ilex.var.TimedVariable;
import decodes.tsdb.TimeSeriesIdentifier;

//AW:IMPORTS
import cwmsdb.* ;
import java.sql.Connection ;
import java.sql.SQLException;
import java.sql.CallableStatement;
import org.jdom.* ;	//classes to represent the components of an XML document
import java.io.IOException;
import java.io.ByteArrayInputStream ;
import java.util.* ;
import hec.io.TimeSeriesContainer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import hec.heclib.util.HecTime;
import hec.script.Constants;
import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.sql.Types;
import java.text.SimpleDateFormat;
import java.util.ArrayList;

@Algorithm(description = "Pulls current time series flow and divides by regulating flow to get a percentage time series for outputting")
public class ComputeGageReleasePercent extends decodes.tsdb.algo.AW_AlgorithmBase
{

	@Input
	public double flowInst;	

	@Output
	public NamedVariable regFlowPer = new NamedVariable("regFlowPer", 0);	


//AW:PROPERTIES
	
	public boolean boolCalcUnderFull = false;
	public String strStage2FlowRatingTemplate = "Stage;Flow.EXSA";
	public String strStage2FlowRatingSpecVersion = "Production";
	String _propertyNames[] = { "boolCalcUnderFull", "strStage2FlowRatingTemplate", "strStage2FlowRatingSpecVersion" };
//AW:PROPERTIES_END

	// Allow javac to generate a no-args constructor.

	/**
	 * Algorithm-specific initialization provided by the subclass.
	 */
	@Override
	protected void initAWAlgorithm( ) throws DbCompException
	{
		_awAlgoType = AWAlgoType.TIME_SLICE;
	}
	
	/**
	 * This method is called once before iterating all time slices.
	 */
	@Override
	protected void beforeTimeSlices() throws DbCompException
	{
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
	@Override
	protected void doAWTimeSlice() throws DbCompException
	{
		// Enter code to be executed at each time-slice.
		//get the connection object to the database from the protected member "TimeSeriesDb tsdb" of the base class of a CCP algorithm
		TimeSeriesIdentifier tsid = getParmTsId("flowInst") ;	//Interface TimeSeriesIdentifier is in package decodes.tsdb
		String strTsId = tsid.getUniqueString() ;
		//get the location id out of the tsid by splitting the String using the dot character which is the delimiter in the CWMS TSID convention
		String[] strArrResult = strTsId.split("\\.") ; 
		String units = "ft";
		String flow_out_units = "cfs";
		String office = "SWT";
		// Get the location we are working with
		String strLocation= strArrResult[0] ;
		//Pulls current TS flow and divides by regulating flow to get a percentage TS for outputting
		// TSID to pull current flow from
		//String tsidFlow = strLocation + ".Flow.Inst.1Hour.0.Ccp-Rev";
		String tz = "US/Central";
		// CwmsTextJdbc txt ;
		try (Connection dbconn = tsdb.getConnection()) {
		// ============================================================================================================
		//                                           FETCH REGULATING FLOW
		// ============================================================================================================
		    Double reg_flow_out;
		    CallableStatement rate_stmt = dbconn.prepareCall("" +
		        "  declare\n" +
		        "    l_ztsv cwms_t_ztsv_array;\n" +
		        "    l_reg_stage binary_double;\n" +
		        "    l_reg_flow binary_double;\n" +
		        "    loc_id varchar2(8) := :1;\n" +
		        "    stage_units varchar2(4) := :2;\n" +
		        "    flow_out_units varchar(4) := :3;\n" +
		        "    office varchar(3) := :4;\n" +
		        "begin\n" +
		        "    l_ztsv :=cwms_level.retrieve_location_level_values(\n" +
		        "                    p_location_level_id => loc_id,\n" +
		        "                    p_level_units       => stage_units,\n" +
		        "                    p_start_time        => sysdate,\n" +
		        "                    p_end_time          => sysdate,\n" +
		        "                    p_timezone_id       => 'UTC',\n" +
		        "                    p_office_id         => office);\n" +
		        "    l_reg_stage := l_ztsv(1).value;\n" +
		        "    begin\n" +
		        "        :5 := cwms_rating.rate_f ( \n" +
		        "                        p_rating_spec => loc_id || '.Stage;Flow.EXSA.PRODUCTION',             \n" +
		        "                        p_value       => l_reg_stage,    \n" +
		        "                        p_units       => cwms_t_str_tab(stage_units, flow_out_units),     \n" +
		        "                        p_time_zone   => 'UTC',\n" +
		        "                        p_office_id   => office);         \n" +
		        "    end; \n" +
		            "    /*dbms_output.put_line(l_reg_flow);*/\n" +
		            "    \n" +
		            "end;\n");
		    String strRatingSpec = String.format("%s.%s.%s", strLocation, strStage2FlowRatingTemplate, strStage2FlowRatingSpecVersion) ;
		    rate_stmt.setString(1, strRatingSpec);
		    rate_stmt.setString(2, "ft");
		    rate_stmt.setString(3, "cfs");
		    rate_stmt.setString(4, office);
		    rate_stmt.registerOutParameter(5, Types.DOUBLE);
		    rate_stmt.execute();
		    reg_flow_out = rate_stmt.getDouble(5);
		    rate_stmt.close();
		// ============================================================================================================
		//                                           END FETCH REGULATING FLOW
		// ============================================================================================================
		    System.out.println("reg flow: "+reg_flow_out);
		    // setup the timeframe for the time series retrieval
		    String start_date = LocalDateTime.now()
		                        .withMinute(0)
		                        .minusHours(2)
		                        .format(DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm"));
		    String end_date = LocalDateTime.now()
		                    .withMinute(0)
		                    .format(DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm"));
		// ============================================================================================================
		//                                           FETCH TIME SERIES 
		// ============================================================================================================
		    // TODO:
		    // We don't need to fetch this if the TS is triggering it we can somehow feed that value in here and use that?
		    TimeSeriesContainer ts_cont = new TimeSeriesContainer();
		    System.out.println("Start: "+ start_date);
		    System.out.println("End: "+ end_date);
		    CallableStatement ts_stmt = dbconn.prepareCall(""
		            + "begin"
		            + "  cwms_ts.retrieve_ts("
		            + "    :1,"
		            + "    :2,"
		            + "    :3,"
		            + "    to_date(:4, 'mm/dd/yyyy hh24:mi'),"
		            + "    to_date(:5, 'mm/dd/yyyy hh24:mi'),"
		            + "    :6);" //'US/Central'
		            + "end;");
		    ts_stmt.registerOutParameter(1, -10); // OracleType.CURSOR or -10
		    ts_stmt.setString(2, strTsId);
		    ts_stmt.setString(3, "cfs");
		    ts_stmt.setString(4, start_date);
		    ts_stmt.setString(5, end_date);
		    ts_stmt.setString(6, tz);
		    HecTime ht = new HecTime();
		    //t.setCurrent();
		    // System.out.println(fetch_time_start);
		    ts_stmt.execute();
		    ResultSet rs = (ResultSet)ts_stmt.getObject(1);
		    List<Timestamp> times = new ArrayList<>();
		    List<Double>    values = new ArrayList<>();
		    List<Integer>   qualities = new ArrayList<>();
		    while (rs.next()) {
		        int quality = rs.getInt(3);
		        // Leave the TS out if it has something wrong with it ( quality = 5) - not transmitted (shows a really small negative value/zero)
		    if (quality != 5) {
		            times.add(rs.getTimestamp(1));
		            values.add(rs.getDouble(2));
		            if (rs.wasNull()) values.set(values.size()-1, Double.NEGATIVE_INFINITY);
		            qualities.add(quality);
		        }
		    }
		    System.out.println(values);
		    rs.close();
		    ts_stmt.close();
		    //-------------------------------------------------//
		    // Build a TS Container                            //
		    //-------------------------------------------------//
		    ts_cont.fullName = strTsId;
		    String[] parts = strTsId.split("\\.");
		    ts_cont.location = parts[0];
		    ts_cont.parameter = parts[1];
		    ts_cont.type = "INST-VAL";
		    ts_cont.units = "cfs";
		    ts_cont.interval = 60;
		    ts_cont.version = parts[5];
		    //System.out.println("Times Size: "+ String.valueOf(times.size()));
		    ts_cont.numberValues = times.size();
		    ts_cont.times = new int[ts_cont.numberValues];
		    ts_cont.values = new double[ts_cont.numberValues];
		    ts_cont.quality = new int[ts_cont.numberValues];
		    SimpleDateFormat sdf = new SimpleDateFormat("ddMMMyyyy, HH:mm");
		    for (int i = 0; i < ts_cont.numberValues; ++i) {
		        ht.set(sdf.format(times.get(i)));
		        ts_cont.times[i] = ht.value();
		        ts_cont.values[i] = values.get(i);
		        ts_cont.quality[i] = qualities.get(i);
		        //System.out.println("Time: "+sdf.format(times.get(i)) + " Value: "+ values.get(i) + " Quality: "+ qualities.get(i));
		    }
		    System.out.println(ts_cont.numberValues);
		// ============================================================================================================
		//                                           END FETCH TIME SERIES 
		// ============================================================================================================
		    double[] ts_values = ts_cont.values;
		    int[] ts_quality = ts_cont.quality;
		    int[] ts_times = ts_cont.times;
		    double cur_flow = ts_values[ts_values.length - 1];
		    int quality = ts_quality[ts_quality.length - 1];
		    double fRegFlow = (cur_flow/reg_flow_out)*100;
		    
		    System.out.print(strLocation + ": " + reg_flow_out + " / " + cur_flow + " is: ");
		    System.out.println("\t"+ fRegFlow);
		    if(fRegFlow < 0.0 && boolCalcUnderFull == false) {
						fRegFlow = 0.0 ;
					}
		            
		    // Assign the value to the output
		    setOutput(regFlowPer, fRegFlow);
		} 
		catch (SQLException e) {
		    System.out.println("SQL error is " + e) ;    
		}
	}

	/**
	 * This method is called once after iterating all time slices.
	 */
	@Override
	protected void afterTimeSlices() throws DbCompException
	{
	}
}
