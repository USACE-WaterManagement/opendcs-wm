/*
* Where Applicable, Copyright 2025 OpenDCS Consortium and/or its contributors
*
* Licensed under the Apache License, Version 2.0 (the "License"); you may not
* use this file except in compliance with the License. You may obtain a copy
* of the License at
*
*   http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
* WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
* License for the specific language governing permissions and limitations
* under the License.
*/
package mil.usace.army.opendcs.support;

import java.io.IOException;

import org.opendcs.database.DatabaseService;
import org.opendcs.database.api.OpenDcsDatabase;
import org.opendcs.utils.logging.OpenDcsLoggerFactory;
import org.slf4j.Logger;

import decodes.db.DatabaseException;
import decodes.tsdb.TsdbAppTemplate;
import decodes.util.CmdLineArgs;
import decodes.util.DecodesSettings;


/**
 * Renders some basic information about computation apps,
 * such as the name, enabled, and properties to standard out.
 * 
 * Currently the primary use case is for USACE's cloud system
 * however, it may be useful to others.
 */
public final class ListCompApps extends TsdbAppTemplate
{
    private static Logger log = OpenDcsLoggerFactory.getLogger();

    
    public static void main(String[] argsRaw)
    {
        CmdLineArgs args = new CmdLineArgs();
        args.parseArgs(argsRaw);
        DecodesSettings settings;
        try
        {
            settings = DecodesSettings.fromProfile(args.getProfile());
            

        }
        catch (IOException ex)
        {
            log.atError().setCause(ex).log("Unable to initialize database connection.");
        }
        
        
    }

}