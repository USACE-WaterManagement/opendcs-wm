package spk.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import decodes.tsdb.DbIoException;
import decodes.tsdb.TimeSeriesDb;

public class DbMigrationHelper {
	
	public static int doModify(TimeSeriesDb db, String q) throws DbIoException {
		try(Connection conn = db.getConnection();
			Statement stmt = conn.createStatement()) {
			return stmt.executeUpdate(q);
		}
		catch(SQLException ex) {
			String msg = "SQL Error in modify query '" + q + "': " + ex;
			throw new DbIoException(msg);
		}
	}

	public static ResultSet doQuery(TimeSeriesDb db, String q) throws DbIoException {
		try(Connection conn = db.getConnection();
			Statement stmt = conn.createStatement()) {
			return stmt.executeQuery(q);
		}
		catch(SQLException ex) {
			String msg = "SQL Error in query '" + q + "': " + ex;
			throw new DbIoException(msg);
		}
	}

}
