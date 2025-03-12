package mil.usace.army.swd.swt.algorithms;


import decodes.tsdb.DbCompException;
import decodes.tsdb.TimeSeriesIdentifier;

import decodes.tsdb.algo.AWAlgoType;

import ilex.var.NamedVariable;
//AW:IMPORTS
import java.sql.CallableStatement;
import java.sql.Connection ;
import java.sql.SQLException;
import java.sql.Types;

import org.opendcs.annotations.PropertySpec;
import org.opendcs.annotations.algorithm.Algorithm;
import org.opendcs.annotations.algorithm.Input;
import org.opendcs.annotations.algorithm.Output;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;




@Algorithm(description = "Pulls current time series flow and divides by regulating flow to get a percentage time series for outputting")
public class ComputeGageReleasePercent extends decodes.tsdb.algo.AW_AlgorithmBase
{
    private static final Logger logger = LoggerFactory.getLogger(ComputeGageReleasePercent.class);
    @Input
    public double flowInst;

    @Output
    public NamedVariable regFlowPer = new NamedVariable("regFlowPer", 0);


//AW:PROPERTIES
    @PropertySpec(value = "false")
    private boolean boolCalcUnderFull = false;
    @PropertySpec(value = "Stage;Flow.EXSA")
    private String strStage2FlowRatingTemplate = "Stage;Flow.EXSA";
    @PropertySpec(value = "Production")
    private String strStage2FlowRatingSpecVersion = "Production";
    @PropertySpec
    private String office;
//AW:PROPERTIES_END

    private Connection connection;
    private CallableStatement rateStmt;

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
        // An alternative optimization is to only gather data in the doAWTimeSlice
        // and then do any actual rating in the afterTimeSlices
        connection = tsdb.getConnection();
        try
        {
            rateStmt = connection.prepareCall("" +
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
        }
        catch (SQLException ex)
        {
            tsdb.freeConnection(connection);
            throw new DbCompException("Unable to prepare statement.", ex);
        }
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
        TimeSeriesIdentifier tsid = getParmTsId("flowInst") ;    //Interface TimeSeriesIdentifier is in package decodes.tsdb
        String strTsId = tsid.getUniqueString() ;
        //get the location id out of the tsid by splitting the String using the dot character which is the delimiter in the CWMS TSID convention
        String[] strArrResult = strTsId.split("\\.") ;

        // Get the location we are working with
        String strLocation= strArrResult[0] ;
        //Pulls current TS flow and divides by regulating flow to get a percentage TS for outputting
        // TSID to pull current flow from
        try
        {
            // ============================================================================================================
        //                                           FETCH REGULATING FLOW
        // ============================================================================================================
            double regFlowOut;

            String strRatingSpec = String.format("%s.%s.%s", strLocation, strStage2FlowRatingTemplate, strStage2FlowRatingSpecVersion) ;
            rateStmt.setString(1, strRatingSpec);
            rateStmt.setString(2, "ft");
            rateStmt.setString(3, "cfs");
            rateStmt.setString(4, office);
            rateStmt.registerOutParameter(5, Types.DOUBLE);
            rateStmt.execute();
            regFlowOut = rateStmt.getDouble(5);

            double fRegFlow = (flowInst/regFlowOut)*100;

            logger.debug("{}: {}/{} is : {}", strLocation, regFlowOut, flowInst, fRegFlow);
            if(fRegFlow < 0.0 && !boolCalcUnderFull)
            {
                        fRegFlow = 0.0 ;
            }

            // Assign the value to the output
            setOutput(regFlowPer, fRegFlow);
        }
        catch (SQLException ex)
        {
            tsdb.freeConnection(connection);
            throw new DbCompException("Unable to perform calculation.", ex);
        }
    }

    /**
     * This method is called once after iterating all time slices.
     */
    @Override
    protected void afterTimeSlices() throws DbCompException
    {
        try
        {
            rateStmt.close();
        }
        catch (SQLException ex)
        {
            throw new DbCompException("Unable to close rating statement.",ex);
        }
        finally
        {
            tsdb.freeConnection(connection);
        }
    }
}
