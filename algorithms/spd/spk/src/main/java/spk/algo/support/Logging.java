/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package spk.algo.support;

import org.opendcs.utils.logging.OpenDcsLoggerFactory;

/**
 
 *  debug logging, shamelessly copied from DbAlgorithmExecutive.java created by Ilex/Sutron
 *  for use in support classes not derived from the above.
 
 * @author L2EDDMAN
 */
public class Logging {
        
        public static void warning(String msg ) {            
            
            OpenDcsLoggerFactory.getLogger().warn(new StringBuilder().append("Library '").append
                                      ("").append
                                      ("' ").append
                                      (msg).toString());
        }

        public static void info(String msg) {
            OpenDcsLoggerFactory.getLogger().info(new StringBuilder().append("Library '").append
                                   (" ").append
                                   ("' ").append
                                   (msg).toString());
        }

        public static void debug1(String msg) {
            OpenDcsLoggerFactory.getLogger().debug(new StringBuilder().append("Library '").append
                                     (" ").append
                                     ("' ").append
                                     (msg).toString());
        }

        public static void debug2(String msg) {
            OpenDcsLoggerFactory.getLogger().debug(new StringBuilder().append("Library '").append
                                     ("").append
                                     ("' ").append
                                     (msg).toString());
        }

        public static void debug3(String msg) {
            OpenDcsLoggerFactory.getLogger().debug(new StringBuilder().append("Library '").append
                                     ("").append
                                     ("' ").append
                                     (msg).toString());
        }

}
