/*
 *  As a work of the United States Government, this package 
 *  is in the public domain within the United States. Additionally, 
 *  We waive copyright and related rights in the work worldwide
 *  through the CC0 1.0 Universal Public Domain Dedication 
 *  (which can be found at https://creativecommons.org/publicdomain/zero/1.0/).

 */
package spk.db;

import decodes.sql.DbKey;
import decodes.tsdb.CTimeSeries;
import decodes.tsdb.DbIoException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import opendcs.dao.DaoBase;
import opendcs.dao.DatabaseConnectionOwner;
import opendcs.dao.DbObjectCache;
import opendcs.dao.SiteDAO;
import opendcs.util.functional.ResultSetConsumer;
import spk.apps.support.Alarms.Threshold;

/**
 *
 * @author Michael Neilson <michael.a.neilson@usace.army.mil>
 */
public class ThresholdDAO 
        extends DaoBase 
	implements ThresholdDAI{

    protected static DbObjectCache<Threshold> cache = new DbObjectCache<Threshold>(15 * 60 * 1000L, false);
    
    
    public ThresholdDAO(DatabaseConnectionOwner db){
        super(db, "ThresholdDAO");
        
        // get properaties and such
    }

    @Override
    public ArrayList<Threshold> getThresholds(DbKey SDI, ArrayList<String> checks) {
       ArrayList<Threshold> thresholds = new ArrayList<Threshold>();
        
        String q = "select from ccp.alarm_thresholds where sitedatatypeid = " + SDI.toString();
        try {
            this.doQuery(q, new ResultSetConsumer() {
                @Override
                public void accept(ResultSet rs) throws SQLException {
                    // and check in checks
                    
                }
            });

            return thresholds;
        } catch (SQLException ex) {
            Logger.getLogger(ThresholdDAO.class.getName()).log(Level.SEVERE, null, ex);
            return null;
        }
        
        /*
            do the sql, build the thresholds
        
        */
        
        
        
    }

    @Override
    public ArrayList<Threshold> getThresholds(CTimeSeries cts, ArrayList<String> checks) {
        return this.getThresholds(cts.getSDI() , checks);        
    }

    @Override
    public void saveThreashold(Threshold threshold) {
        // check for existing threshold id
        // check for existing check of same parameters ( SDI, check, a,b,duration )
        
        
    }

    
}
