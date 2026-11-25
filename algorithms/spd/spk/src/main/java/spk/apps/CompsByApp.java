/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package spk.apps;
import decodes.tsdb.TsdbAppTemplate;
import decodes.tsdb.DbComputation;
import decodes.tsdb.DbAlgorithmExecutive;
import decodes.util.CmdLineArgs;
import ilex.cmdline.StringToken;
import ilex.cmdline.TokenOptions;
import java.io.PrintStream;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import lrgs.gui.DecodesInterface;
import opendcs.dai.LoadingAppDAI;




/**
 * Runs a simple query to show the status of the system
 * @author L2EDDMAN
 */
public class CompsByApp extends TsdbAppTemplate {

    //private StringToken compname = new StringToken("C","Computation to Run","", TokenOptions.optSwitch, "" );
    
    public CompsByApp(){
        super("compsbyapp.log");
     
    }
    
    @Override
    public void initDecodes(){
        //skip this
        
    }
    
    @Override
    protected void addCustomArgs( CmdLineArgs cmdLineArgs){
        
     
        appNameArg.setDefaultValue("compsbyapp");
    }
    
    
    @Override
    protected void runApp() throws Exception {
        //System.out.println( this.compname.getValue() );                             
        System.out.println( "Query Database");
        
        try(LoadingAppDAI dai = theDb.makeLoadingAppDAO();
            ResultSet rs = dai.doQuery( "select a.loading_application_id," +
                "a.loading_application_name as App," +
                "(select count(b.site_datatype_id) from ccp.cp_comp_tasklist b where a.loading_application_id = b.loading_application_id) as num_comps " +
                "from ccp.hdb_loading_application a order by num_comps desc" );
        ) {
            System.out.printf("%10s %-20s %10s\n", "AppID", "App Name","Num Comps");
        
            while( rs.next() )
            {
                long id = rs.getLong(1);
                String name = rs.getString(2);
                int count = rs.getInt(3);
                System.out.printf("%10d %-20s %10d\n", id, name, count);
            }
        }
    }
    
    public static void main( String args[]){
        CompsByApp byapp = new CompsByApp();
        try {
            DecodesInterface.setGUI(false);
            
            byapp.execute(args);
        } catch (Exception ex) {
            Logger.getLogger(CompsByApp.class.getName()).log(Level.SEVERE, null, ex);
        }
        
    }
    
}
