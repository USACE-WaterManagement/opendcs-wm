package mil.usace.army.swd.swt.algorithms;

import ilex.var.NamedVariableList;
import ilex.var.NamedVariable;
import decodes.tsdb.DbAlgorithmExecutive;
import decodes.tsdb.DbCompException;
import decodes.tsdb.DbIoException;
import decodes.tsdb.VarFlags;
import decodes.tsdb.algo.AWAlgoType;
import decodes.cwms.CwmsTimeSeriesDb;
import decodes.cwms.rating.CwmsRatingDao;
import decodes.tsdb.CTimeSeries;
import decodes.tsdb.ParmRef;
import ilex.var.TimedVariable;
import usace.cwms.db.dao.ifc.level.CwmsDbLevel;
import usace.cwms.db.dao.ifc.text.CwmsDbText;
import usace.cwms.db.dao.ifc.ts.CwmsDbTs;
import usace.cwms.db.dao.util.TimeValueQuality;
import usace.cwms.db.dao.util.services.CwmsDbServiceLookup;
import wcds.dbi.oracle.cwms.CwmsTextJdbcDao;
import decodes.tsdb.TimeSeriesIdentifier;

//AW:IMPORTS
// Place an import statements you need here.

import java.sql.Connection ;
import java.sql.SQLException;
import java.sql.CallableStatement;

import org.jdom.Element;
import org.jdom.JDOMException;
import org.opendcs.annotations.PropertySpec;
import org.opendcs.annotations.algorithm.Algorithm;
import org.opendcs.annotations.algorithm.Input;
import org.opendcs.annotations.algorithm.Output;

import java.io.IOException;
import java.io.ByteArrayInputStream ;
import java.util.* ;
//AW:IMPORTS_END

@Algorithm(description = "This algorithm calculates pool storage and pool percent based on a storage value in a time series.")
public class PoolStorageAndPoolPercent extends decodes.tsdb.algo.AW_AlgorithmBase
{
    @Input
    public double storInst;

    @Output
    public NamedVariable poolStor = new NamedVariable("poolStor", 0);
    @Output
    public NamedVariable poolPercent = new NamedVariable("poolPercent", 0);
    

    @PropertySpec(value = "true", description = "Calculate if Over full")
    public boolean boolCalcOverFull = true;
    @PropertySpec(value = "true", description = "Calculate if Under full")
    public boolean boolCalcUnderFull = true;
    @PropertySpec()
    public String strPoolName = "";
    @PropertySpec(value="Elev;Stor.Linear")
    public String strElev2StorRatingTemplate = "Elev;Stor.Linear";
    @PropertySpec(value="Production")
    public String strElev2StorRatingSpecVersion = "Production";

    private Connection conn = null;
    private CwmsDbText cwmsText;
    private CwmsDbLevel cwmsLevel;
    private CwmsRatingDao cwmsRatingDao;

    // Allow javac to generate a no-args constructor.

    /**
     * Algorithm-specific initialization provided by the subclass.
     */
    @Override
    protected void initAWAlgorithm() throws DbCompException
    {
        _awAlgoType = AWAlgoType.TIME_SLICE;

    }

    /**
     * This method is called once before iterating all time slices.
     */
    @Override
    protected void beforeTimeSlices() throws DbCompException
    {
        conn = tsdb.getConnection();
        try
        {
            cwmsText = CwmsDbServiceLookup.buildCwmsDb(CwmsDbText.class, conn);
            cwmsLevel = CwmsDbServiceLookup.buildCwmsDb(CwmsDbLevel.class, conn);
            cwmsRatingDao = new CwmsRatingDao((CwmsTimeSeriesDb)tsdb);;
        }
        catch (SQLException ex)
        {
            tsdb.freeConnection(conn);
            throw new DbCompException("Unable to retrieve Cwms Text Utility.", ex);
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
        //call the base class method getParmTsId() with the role name.  That is, whatever your algorithm is calling the parameter (e.g. "inputParam", "indepThing") as it appears in the group box 'Input Time Series' above.
        TimeSeriesIdentifier tsid = getParmTsId("storInst") ;   //Interface TimeSeriesIdentifier is in package decodes.tsdb
        String strTsId = tsid.getUniqueString() ;
        //System.out.println("The tsid of input storInst is " + strTsId) ;
        //get the location id out of the tsid by splitting the String using the dot character which is the delimiter in the CWMS TSID convention
        String[] strArrResult = strTsId.split("\\.") ;  //this looks strange because the dot in regular expressions means "any character" so therefore you have to escape the dot
        //System.out.println("location id is therefore " + strArrResult[0]) ;
        //System.out.println("  -->current timeslice is " + _timeSliceBaseTime.toString()) ;
        //get and parse the XML CLOB that specifies the pool information
        
        
        try {
            //obtain the CLOB containing the XML that describes the min and max location levels for the pool
            
            String strLocation= strArrResult[0] ;
            String strTextKey = String.format("POOL.%s.%s", strLocation, strPoolName) ;
            String strCwmsText = cwmsText.retrieveText(conn, strTsId);
            //now parse the XML into a jdom class object
            org.jdom.Document doc = null;
            Element root = null ;
            org.jdom.input.SAXBuilder parser = new org.jdom.input.SAXBuilder(false) ;  //create a SAX parser with no validation
            if (parser == null)
            {
                System.out.println("SAXBuilder default CTOR failed") ;
            }
            else
            {
                doc = parser.build(new ByteArrayInputStream(strCwmsText.getBytes("UTF-8"))) ;
            }
            root = doc.getRootElement() ;
                //System.out.println("  rootname: "+ root.getName()) ;
                //from the root document, navigate down to the min_loc_level_ref section and query for the string that contains it's id
                ArrayList arrlist1 = new ArrayList() ;
                arrlist1.addAll(root.getChildren("min_loc_level_ref")) ;
                Element elemSpecifiedLevelMin = (Element)arrlist1.get(0) ;
                //System.out.println("name is " + elemSpecifiedLevelMin.getName() );

                ArrayList arrlist2 = new ArrayList() ;
                arrlist2.addAll(elemSpecifiedLevelMin.getChildren("specified_level")) ;
                Element elemSpecifiedLevelMinId = (Element)arrlist2.get(0) ;
                //System.out.println("name is " + elemSpecifiedLevelMinId.getName() );
                String strPoolMinLevelId = elemSpecifiedLevelMinId.getAttributeValue("id") ;
                //System.out.println(" min_loc_level_ref id = " + strPoolMinLevelId);

                //from the root document, navigate down to the max_loc_level_ref section and query for the string that contains it's id
                ArrayList arrlist3 = new ArrayList() ;
                arrlist3.addAll(root.getChildren("max_loc_level_ref")) ;
                Element elemSpecifiedLevelMax = (Element)arrlist3.get(0) ;
                //System.out.println("name is " + elemSpecifiedLevelMax.getName() );

                ArrayList arrlist4 = new ArrayList() ;
                arrlist4.addAll(elemSpecifiedLevelMax.getChildren("specified_level")) ;
                Element elemSpecifiedLevelMaxId = (Element)arrlist4.get(0) ;
                //System.out.println("name is " + elemSpecifiedLevelMaxId.getName() );
                String strPoolMaxLevelId = elemSpecifiedLevelMaxId.getAttributeValue("id") ;
            //System.out.println(" max_loc_level_ref id = " + strPoolMaxLevelId);

                //build the sql call to retrieve the elev value for the min loc level for the pool
                String strLocLevelId = String.format("%s.Elev.Inst.0.%s", strLocation, strPoolMinLevelId) ;
                //System.out.println(strLocLevelId);

                // create a UTC Time timezone
                TimeZone UTC = TimeZone.getTimeZone("London");
                // create a Central Time timezone .....NEEDS REWORKED TO MAKE THIS ALGO USEFUL ACROSS THE NATION
                TimeZone US_Central = TimeZone.getTimeZone("US/Central");
                GregorianCalendar calTimeSlice = new GregorianCalendar(US_Central) ;
                calTimeSlice.setTime(_timeSliceBaseTime) ; //the class variable _timeSliceBaseTime is of type Date and is in the base class AW_AlgorithmBase
                int sliceYear = calTimeSlice.get(Calendar.YEAR) ;
                int sliceMon = calTimeSlice.get(Calendar.MONTH) ; sliceMon++ ;  //incrementing by one is necessary because the GregorianCalendar class counts months using a zero based offset.  e.g. January == 0
                int sliceDay = calTimeSlice.get(Calendar.DAY_OF_MONTH) ;
                int sliceHour = calTimeSlice.get(Calendar.HOUR_OF_DAY) ;        //get the hour in 24-hr format e.g 13 is 1 p.m.

                String strTimeSliceDateTime = String.format("%d/%02d/%02d %02d:00:00", sliceYear, sliceMon, sliceDay, sliceHour) ;
                //System.out.println("timeSlice is " + strTimeSliceDateTime) ;

                String strResult = "" ;
                                



                List<TimeValueQuality> result = cwmsLevel.retrieveLocationLevelValues(conn, strLocLevelId, "ft", _timeSliceBaseTime, _timeSliceBaseTime, null, null, null, aggTZ.getID(), null);
                //parse the records in the resultset string
                char delimiter[] = {'a'} ;
                delimiter[0] = (char)30 ;
                String strDelimiter= new String(delimiter) ;
                String[] strArrayResult = strResult.split(strDelimiter) ;

                //parse the fields in the first record
                delimiter[0] = (char)29 ;
                strDelimiter= new String(delimiter) ;
                String[] strArrayFields = strArrayResult[0].split(strDelimiter) ;

                float fElevAtMinLocLevel = Float.parseFloat(strArrayFields[1]) ;

                //System.out.println(strLocLevelId + "  " + fElevAtMinLocLevel);
                //build the sql call to retrieve the elev value for the max loc level for the pool
                strLocLevelId = String.format("%s.Elev.Inst.0.%s", strLocation, strPoolMaxLevelId) ;
                //System.out.println(strLocLevelId);

                CallableStatement cstmt2 = dbconn.prepareCall("{call ? := CWMS_LEVEL.RETRIEVE_LOC_LVL_VALUES2 (?, ?, ?, ?, ?, ?, ?, ?, ?)}") ;
                cstmt2.registerOutParameter(1, java.sql.Types.VARCHAR) ;
                cstmt2.setString(2, strLocLevelId) ; //(will be of the form: "KAWL.Elev.Inst.0.Top of Flood") ;
                cstmt2.setString(3, "ft") ;
                cstmt2.setString(4, strTimeSliceDateTime) ; //cstmt.setString(4, "2014/09/29 12:00:00") ;
                cstmt2.setString(5, strTimeSliceDateTime) ; //cstmt.setString(5, "2014/09/29 12:00:00") ;
                cstmt2.setNull(6, java.sql.Types.VARCHAR) ;
                cstmt2.setNull(7, java.sql.Types.NUMERIC) ;
                cstmt2.setNull(8, java.sql.Types.VARCHAR) ;
                cstmt2.setString(9, "US/Central") ;  //.....NEEDS REWORKED TO MAKE THIS ALGO USEFUL ACROSS THE NATION
                cstmt2.setNull(10, java.sql.Types.VARCHAR) ;    //needs modified in order to work in CWMS databases that hosts multiple offices
                bexe = cstmt2.execute() ;
                strResult = cstmt2.getString(1) ;
                //System.out.println(strResult) ;
                cstmt2.close();

                //parse the records in the resultset string
                delimiter[0] = (char)30 ;
                strDelimiter= new String(delimiter) ;
                String[] strArrayResult2 = strResult.split(strDelimiter) ;

                //parse the fields in the first record
                delimiter[0] = (char)29 ;
                strDelimiter= new String(delimiter) ;
                String[] strArrayFields2 = strArrayResult2[0].split(strDelimiter) ;

                float fElevAtMaxLocLevel = Float.parseFloat(strArrayFields2[1]) ;

                //System.out.println(strLocLevelId + "  " + fElevAtMaxLocLevel);
                //rate the elev that was retrieved from the pool's min location level
                CallableStatement cstmt4  ;
                String sql =
                            "declare\n"
                        + "l_rated cwms_t_double_tab;\n"
                        + "begin\n"
                        + "l_rated := cwms_rating.rate_f(\n"
                        + "  :1,                                             -- p_rating_spec \n"
                        + "      cwms_t_double_tab_tab(cwms_t_double_tab(:2)),   -- p_values      \n"
                        + "      cwms_t_str_tab(:3, :4),                         -- p_units       \n"
                        + "  'F',                                            -- p_round       \n"
                        + "      null,                                           -- p_value_times \n"
                        + "      null,                                           -- p_rating_time \n"
                        + "  null,                                           -- p_time_zone   \n"
                        + "      :5);                                            -- p_office_id   \n"
                        + ":6 := l_rated(1);\n"
                        + "end; \n" ;

                cstmt4 = dbconn.prepareCall(sql) ;
                cstmt4.registerOutParameter(6, java.sql.Types.NUMERIC);
                String strRatingSpec = String.format("%s.%s.%s", strLocation, strElev2StorRatingTemplate, strElev2StorRatingSpecVersion) ; //of the form: "KEYS.Elev;Stor.Linear.Production"
                cstmt4.setString(1, strRatingSpec);
                cstmt4.setDouble(2, fElevAtMinLocLevel) ;   // elevation
                cstmt4.setString(3, "ft") ;  // elevation
                cstmt4.setString(4, "ac-ft") ; // flow
                cstmt4.setNull(5, java.sql.Types.VARCHAR) ;     //needs modified in order to work in CWMS databases that hosts multiple offices

                cstmt4.execute() ;

                double fStorAtMinLocLevel = cstmt4.getDouble(6) ;
                //System.out.println("Stor of " + strPoolMinLevelId + " is "+ fStorAtMinLocLevel + " ac-ft") ;
                cstmt4.close() ;
                //rate the elev that was retrieved from the pool's max location level
                CallableStatement cstmt6  ;
                sql =
                            "declare\n"
                        + "l_rated cwms_t_double_tab;\n"
                        + "begin\n"
                        + "l_rated := cwms_rating.rate_f(\n"
                        + "  :1,                                             -- p_rating_spec \n"
                        + "      cwms_t_double_tab_tab(cwms_t_double_tab(:2)),   -- p_values      \n"
                        + "      cwms_t_str_tab(:3, :4),                         -- p_units       \n"
                        + "  'F',                                            -- p_round       \n"
                        + "      null,                                           -- p_value_times \n"
                        + "      null,                                           -- p_rating_time \n"
                        + "  null,                                           -- p_time_zone   \n"
                        + "      :5);                                            -- p_office_id   \n"
                        + ":6 := l_rated(1);\n"
                        + "end; \n" ;

                cstmt6 = dbconn.prepareCall(sql) ;
                cstmt6.registerOutParameter(6, java.sql.Types.NUMERIC);
                strRatingSpec = String.format("%s.%s.%s", strLocation, strElev2StorRatingTemplate, strElev2StorRatingSpecVersion) ; //of the form: "KEYS.Elev;Stor.Linear.Production"
                cstmt6.setString(1, strRatingSpec);
                cstmt6.setDouble(2, fElevAtMaxLocLevel) ;   // elevation
                cstmt6.setString(3, "ft") ;  // elevation
                cstmt6.setString(4, "ac-ft") ; // flow
                cstmt6.setNull(5, java.sql.Types.VARCHAR) ;     //needs modified in order to work in CWMS databases that hosts multiple offices

                cstmt6.execute() ;

                double fStorAtMaxLocLevel = cstmt6.getDouble(6) ;
                //System.out.println("Stor of " + strPoolMaxLevelId + " is "+ fStorAtMaxLocLevel + " ac-ft") ;
                cstmt6.close() ;

                double fStorAtTimeSlice = storInst ;
                double fPoolStorTotal = fStorAtMaxLocLevel - fStorAtMinLocLevel ;
                double fPoolStorFilled = fStorAtTimeSlice - fStorAtMinLocLevel ;
                double fPoolStorEmpty = fPoolStorTotal - fPoolStorFilled ;
                double fPoolFilledPercent = (fPoolStorFilled / fPoolStorTotal) * 100.0 ;
                if(fPoolFilledPercent > 100.0 && boolCalcOverFull == false) {
                        fPoolStorFilled = fPoolStorTotal ;
                        fPoolFilledPercent = 100.0 ;
                } //end if (executes if the pool is filled to over 100% and the flag for whether to compute overages is false...in other words, cap the % full at 100%
                if(fPoolFilledPercent < 0.0 && boolCalcUnderFull == false) {
                        fPoolStorFilled = 0.0 ;
                        fPoolFilledPercent = 0.0 ;
                } //end if (executes if the pool is filled to over 100% and the flag for whether to compute overages is false...in other words, cap the % full at 100%
                //set the outputs
                setOutput(poolStor, fPoolStorFilled) ;
                setOutput(poolPercent, fPoolFilledPercent) ;

                //System.out.println("\n Pool Stor Total = " + fPoolStorTotal) ;
                //System.out.println(" Pool Stor Filled = " + fPoolStorFilled) ;
                //System.out.println(" Percent Pool Stor Filled = " + fPoolFilledPercent) ;
        } //end try block
        catch (SQLException e) {
                System.out.println("SQL error is " + e) ;
        }
        catch (JDOMException jdomEx) {
                System.out.println("JDOM exception is " + jdomEx) ;
        } //end catch block
        catch (IOException ioEx) {
                System.out.println("JDOM exception is " + ioEx) ;
        } //end catch block
//AW:TIMESLICE_END
    }

    /**
     * This method is called once after iterating all time slices.
     */
    protected void afterTimeSlices()
            throws DbCompException
    {
        cwmsRatingDao.close();
        tsdb.freeConnection(conn);
    }
}
