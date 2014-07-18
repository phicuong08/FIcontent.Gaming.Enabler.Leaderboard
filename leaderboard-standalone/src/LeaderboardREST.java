// leaderboard server with rest api, supporting json or jsonp

import java.io.IOException;

// rest server
import static spark.Spark.*;
import spark.*;

// mysql
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.sql.Blob;

// logging
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

// json
//import com.google.gson.Gson;
//import com.google.gson.JsonArray;
//import com.google.gson.JsonParser;
//import java.util.ArrayList;
//import java.util.Collection;
// json-minimal
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import com.eclipsesource.json.ParseException;

// base64 conversion
//import sun.misc.BASE64Decoder;
import org.apache.commons.codec.binary.Base64;
import java.util.Arrays;

public class LeaderboardREST {
	public static final boolean DEBUG = true;

	static ServerSettings settings;

	static String configFileName = "config.properties";
	static Logger logger;

	///////////////////////////////////////////////////////////////////////////////////////

	public static int toIntDef(String v, int defaultValue) {
		int result = defaultValue;
		try {
			result = Integer.parseInt(v);
		} catch (Exception e) {
		}
		return result;
	}

	public static boolean toBoolDef(String v, boolean defaultValue) {
		boolean result = defaultValue;
		try {
			result = Boolean.parseBoolean(v);
		} catch (Exception e) {
		}
		return result;
	}

	public static String toSafeString(String s, int maxLength) { // this should return a string that is safe to use in sql inputs, preventing sql injection
		if (s == null) return null;

		try {
			s = java.net.URLDecoder.decode(s, "UTF-8"); // to enable spaces
		} catch (Exception e) {return null;};

		int len = s.length();
		if (len > maxLength) len = maxLength;
		StringBuilder res = new StringBuilder(len);
		for (int i=0; i<len; i++) {
			char ch = s.charAt(i);
			if (   ((ch >= 'a') && (ch <= 'z')) // be very careful when changing this! besides ',\,; there are also comment charaters etc.
				|| ((ch >= 'A') && (ch <= 'Z'))
				|| ((ch >= '0') && (ch <= '9'))
				|| (ch == '.') || (ch == ' ') )
			res.append(ch);
		}

		return res.toString();
	}

	///////////////////////////////////////////////////////////////////////////////////////

	public static void main(String[] args) {
		settings = new ServerSettings(configFileName);
		///////////////////

		// init logger (to file)
		logger = Logger.getLogger("MyLog");  
		FileHandler fh;  

		if (settings.logfile != null) try {
			// This block configure the logger with handler and formatter
			fh = new FileHandler(settings.logfile, 100000, 1, true);
			logger.addHandler(fh);
			SimpleFormatter formatter = new SimpleFormatter();
			fh.setFormatter(formatter);
			// the following statement is used to log any messages
			logger.info("My first log");
		} catch (SecurityException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}  

		logger.info("//////////// LeaderboardREST server started /////////////");  

	  // for browser (FireFox) cross origin resource sharing (CORS) to confirm that PUT and DELETE work, too
      options(new Route("/lb/:gameID") {
         @Override
         public Object handle(Request request, Response response) {
			if (DEBUG) System.out.println("OPTIONS");
			response.header("Access-Control-Allow-Origin", "*");
			response.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
			response.header("Access-Control-Allow-Headers", "X-Requested-With");
			response.header("Access-Control-Max-Age", "100");
			response.status(204);
			return ("");
		}
	  });


	  /////////////////// post score ///////////////////

      post(new Route("/lb/:gameID/:playerID/score") {
         @Override
         public Object handle(Request request, Response response) {
			String callback = request.queryParams("callback");

			String gameID = toSafeString(request.params(":gameID"), 30);
			String playerID = toSafeString(request.params(":playerID"), 30);
			if (DEBUG) {
				System.out.println(gameID);
				System.out.println(playerID);

				System.out.println("queryparams:");
				System.out.println(request.queryParams());
				System.out.println("body:");
				System.out.println(request.body());
			}

			// read other parameters
			String content = request.body();
			//String content = request.queryParams().toString(); // this wraps the content in an additional array '[...]'
			String names = "";
			String values = "";
			int newHighScore = 0;
			byte[] userData = null;
			try {
				JsonObject requestObj = JsonObject.readFrom(content);
				//JsonObject requestObj = JsonArray.readFrom(content).get(0).asObject(); // strip the additional '[...]' introduced above
				JsonArray scoresArr = requestObj.get("scoreEntries").asArray();
				for (int i=0; i<scoresArr.size(); i++) {
					JsonObject scoreEntryObj = scoresArr.get(i).asObject();
					if (i>0) {
						names += ',';
						values += ',';
					}
					String nName = toSafeString(scoreEntryObj.get("name").asString(), 30);
					names += nName;
					int nValue = Integer.parseInt(scoreEntryObj.get("value").asString()); // convert to int as safe method to prevent injection
					values += nValue;
					if (nName.equals("highscore")) newHighScore = nValue;
				}

				JsonValue userDataBase64 = requestObj.get("userData");					
				if (userDataBase64!=null) userData = Base64.decodeBase64(userDataBase64.asString().getBytes()); // decode base64 to binary

			} catch (ParseException ex) {
				System.out.println("JSON parse exception: " + ex.getMessage());
				System.out.println(content);
				response.status(400); // 400 bad request
				return "Cannot parse JSON payload. " + ex.getMessage();
			} catch (Exception ex) {
				response.status(500); // 500 internal server error
				System.out.println("submitscore: exception " + ex.getMessage());
				ex.printStackTrace();
				return "Cannot read input. " + ex.getMessage();
			}

			if (DEBUG) {
				System.out.println(names);
				System.out.println(values);
			}

			Connection con = null;
			Statement st = null;
			ResultSet rs = null;

			response.type("application/json");

			StringBuilder result = new StringBuilder(40);
			if (callback != null) {
				result.append(callback);
				result.append("(");
			}

			String prevBestPlayerID = null;
			String newBestPlayerID = null;

			String socialnetwork = null;
			try {
				con = DriverManager.getConnection(settings.url, settings.user, settings.password);

				// read options from $options table
				GameOptions options = GameOptions.getGameOptions(gameID, con);

				/////// get previous best player id
				if (options.getSocialNetwork() != null) {
					st = con.createStatement();
					String qustr = "SELECT x.position FROM (SELECT mygame." + gameID + ".highscore,mygame." + gameID + ".playerID,@rownum:=@rownum+1 AS POSITION from mygame." + gameID + " JOIN (select @rownum:=0) r ORDER by mygame." + gameID + ".highscore DESC) x WHERE x.playerID=\'" + playerID + "\' order by position limit 1;";
					if (DEBUG) System.out.println(qustr + "\n");
					rs = st.executeQuery(qustr);
					ResultSetMetaData rsmd = rs.getMetaData();
					if (rs.next()) { // only first position
						prevBestPlayerID = rs.getString(1);
					}
				}
				/////// end get previous best player id

				//con = DriverManager.getConnection(url, user, password);
				if (!options.getOnlyKeepBestEntry()) {
					String updstr = "INSERT INTO mygame." + gameID + " (playerID," + ((userData!=null)?"userData,":"") + names + ") VALUES (?," + ((userData!=null)?"?,":"") + values + ");";
					if (DEBUG) System.out.println(updstr + "\n");
					PreparedStatement pst = con.prepareStatement(updstr);
					pst.setString(1, playerID);
					if (userData!=null) pst.setBytes(2, userData);
					pst.executeUpdate();
				} else {
					//// test if exists and which value
					con = DriverManager.getConnection(settings.url, settings.user, settings.password);
					st = con.createStatement();
					String qustr = "SELECT highscore FROM mygame." + gameID + " WHERE playerID = \'" + playerID + "\';";
					if (DEBUG) System.out.println(qustr + "\n");
					rs = st.executeQuery(qustr);
					ResultSetMetaData rsmd = rs.getMetaData();
					boolean exists = false;
					int currentBestScore = 0;
					if (rs.next()) { // only first position
						exists = true;
						currentBestScore = rs.getInt("highscore");
					}


					//// if not exists or if old value is lower, replace/insert
					if (DEBUG) {
						System.out.println("current score: " + currentBestScore);
						System.out.println("new high score: " + newHighScore);
					}
					if (!exists || (newHighScore > currentBestScore)) {
//						st = con.createStatement();
//						String updstr = "REPLACE INTO mygame." + gameID + " (playerID," + names + ") VALUES (\'" + playerID + "\'," + values + ");";
//						st.executeUpdate(updstr);
						String updstr = "REPLACE INTO mygame." + gameID + " (playerID," + ((userData!=null)?"userData,":"") + names + ") VALUES (?," + ((userData!=null)?"?,":"") + values + ");";
						if (DEBUG) System.out.println(updstr + "\n");
						PreparedStatement pst = con.prepareStatement(updstr);
						pst.setString(1, playerID);
						if (userData!=null) pst.setBytes(2, userData);
						pst.executeUpdate();
					}
				}


				////
				// delete entries if too many
				//delete from TableName where entityID not in (select top 1000 entityID from TableName)
				if (options.getMaxEntries() > 0) {
					st = con.createStatement();
					//get highscore of entry number maxEntries
					String qustr = "SELECT highscore FROM mygame." + gameID + " ORDER by highscore DESC limit " + options.getMaxEntries() + ",1;";
					if (DEBUG) System.out.println(qustr + "\n");
					rs = st.executeQuery(qustr);
					ResultSetMetaData rsmd = rs.getMetaData();
					boolean exists = false;
					int score = 0;
					if (rs.next()) { // only first position
						exists = true;
						score = rs.getInt("highscore");
					}

					if (exists) {
						if (DEBUG) System.out.println("deleting all scores below: " + score);
						// delete rows with highscore below threshold
						st = con.createStatement();
						String updstr = "DELETE FROM mygame." + gameID + " where highscore < " + score + ";";
						if (DEBUG) System.out.println(updstr + "\n");
						st.executeUpdate(updstr);
					}
				}
				////

				/////// get new best player id
				if (options.getSocialNetwork() != null) {
					st = con.createStatement();
					String qustr = "SELECT x.position FROM (SELECT mygame." + gameID + ".highscore,mygame." + gameID + ".playerID,@rownum:=@rownum+1 AS POSITION from mygame." + gameID + " JOIN (select @rownum:=0) r ORDER by mygame." + gameID + ".highscore DESC) x WHERE x.playerID=\'" + playerID + "\' order by position limit 1;";
					if (DEBUG) System.out.println(qustr + "\n");
					rs = st.executeQuery(qustr);
					ResultSetMetaData rsmd = rs.getMetaData();
					if (rs.next()) { // only first position
						newBestPlayerID = rs.getString(1);
					}
				}
				/////// end get new best player id

				response.status(200); // 200 ok
			} catch (SQLException ex) {
				response.status(503); // 503 Service Unavailable
				logger.log(Level.SEVERE, ex.getMessage(), ex);
//			} catch (Exception e) {
//				response.status(503); // 503 Service Unavailable
			} finally {
				try {
					if (st != null) {
						st.close();
					}
					if (con != null) {
						con.close();
					}

				} catch (SQLException ex) {
					logger.log(Level.WARNING, ex.getMessage(), ex);
				}
			}

			// social network
			if (DEBUG) System.out.println("posting to social network...");
			if ((socialnetwork != null) && (prevBestPlayerID != null) && (newBestPlayerID != null)
				//&& !prevBestPlayerID.equals(newBestPlayerID)) post2SocialNetwork(socialnetwork, gameID, playerID, newHighScore);
				&& !prevBestPlayerID.equals(newBestPlayerID)) SocialNetwork.post(socialnetwork, gameID, playerID, newHighScore);
			if (DEBUG) System.out.println("...done posting to social network");

			return "";
		 }
      });


	  /////////////////// get ranking position ///////////////////

      get(new Route("/lb/:gameID/:playerID/rankingposition") {
         @Override
         public Object handle(Request request, Response response) {
			String callback = request.queryParams("callback");
 
			String gameID = toSafeString(request.params(":gameID"), 30);
			String playerID = toSafeString(request.params(":playerID"), 30);

			// read other parameters
			String orderBy = toSafeString(request.queryParams("orderBy"), 30);
			if (orderBy == null) orderBy = "highscore";
			//String higherIsBetterStr = request.queryParams("higherIsBetter");
			//boolean higherIsBetter = (higherIsBetterStr==null) || (!higherIsBetterStr.equals("false")); // set to true (default) if not param set to false

			Connection con = null;
			Statement st = null;
			ResultSet rs = null;

			response.type("application/json");

			StringBuilder result = new StringBuilder(40);
			if (callback != null) {
				result.append(callback);
				result.append("(");
			}

			try {
				con = DriverManager.getConnection(settings.url, settings.user, settings.password);
				st = con.createStatement();

				String qustr = "SELECT x.position FROM (SELECT mygame." + gameID + ".highscore,mygame." + gameID + ".playerID,@rownum:=@rownum+1 AS POSITION from mygame." + gameID + " JOIN (select @rownum:=0) r ORDER by mygame." + gameID + "." + orderBy + " DESC) x WHERE x.playerID=\'" + playerID + "\' order by position limit 1;";
				if (DEBUG) System.out.println(qustr + "\n");

				rs = st.executeQuery(qustr);

				ResultSetMetaData rsmd = rs.getMetaData();
				int columnCount = rsmd.getColumnCount();

				if (rs.next()) { // only first position
					String pos = rs.getString(1);
					if (pos != null) {
						response.status(200); // 200 ok
						result.append("{position:");
						result.append(pos);
						result.append('}');
					} else {
						// no such player id in database
						response.status(404); // 404 Not Found
					}
				}
				if (callback != null) {
					result.append(");");
				}

				if (DEBUG) System.out.println(result.toString() + "\n");

			} catch (SQLException ex) {
				response.status(503); // 503 Service Unavailable
				logger.log(Level.SEVERE, ex.getMessage(), ex);
//			} catch (Exception e) {
//				response.status(503); // 503 Service Unavailable
			} finally {
				try {
					if (rs != null) {
						rs.close();
					}
					if (st != null) {
						st.close();
					}
					if (con != null) {
						con.close();
					}

				} catch (SQLException ex) {
					logger.log(Level.WARNING, ex.getMessage(), ex);
				}
			}

			return result.toString();
		 }
      });


	  /////////////////// get ranked (ordered) list of entries ///////////////////

      get(new Route("/lb/:gameID/rankedlist") {
         @Override
         public Object handle(Request request, Response response) {
			String callback = request.queryParams("callback");
 
			String gameID = toSafeString(request.params(":gameID"), 30);

			// read other parameters
			int rankStart = toIntDef(request.queryParams("rankStart"), 0); // todo: check if 0 is ok as default?
			int rankEnd = toIntDef(request.queryParams("rankEnd"), 0); // todo: check if 0 is ok as default?
			String orderBy = toSafeString(request.queryParams("orderBy"), 30);
			if (orderBy == null) orderBy = "highscore";

			Connection con = null;
			Statement st = null;
			//PreparedStatement st = null;
			ResultSet rs = null;

			//response.type("text/xml");
			response.type("application/json");

			StringBuilder result = new StringBuilder(400);
			if (callback != null) {
				result.append(callback);
				result.append("(");
			}

			try {
				con = DriverManager.getConnection(settings.url, settings.user, settings.password);

				String qustr = "SELECT * FROM mygame." + gameID + " ORDER BY " + orderBy + " DESC" + ((rankStart+rankEnd > 0) ? (" LIMIT " + rankStart + "," + rankEnd) : "") + ";";
				if (DEBUG) System.out.println(qustr + "\n");

				st = con.createStatement();
				rs = st.executeQuery(qustr);

				ResultSetMetaData rsmd = rs.getMetaData();
				int columnCount = rsmd.getColumnCount();

				result.append('[');
				boolean firstRow = true;
				while (rs.next()) {
					if (!firstRow) result.append(',');
					result.append('{');
					boolean firstOutpCol = true;
					for (int i=1; i<=columnCount; i++) {
						if (rs.getObject(i) != null) {
							if (!firstOutpCol) result.append(',');
							String column = rsmd.getColumnName(i);
							String value;
							if (!column.equals("userData")) {
								value = rs.getString(i);
							} else {
								Blob blob = rs.getBlob(i);
								value = Base64.encodeBase64String(blob.getBytes(1, (int) blob.length()));
								//value = Base64.encodeBase64String(rs.getBytes(i)); // this is supposed to be slower than using getBlob
							}
							result.append('\"');
							result.append(column);
							result.append("\":\"");
							result.append(value);
							result.append('\"');
							firstOutpCol = false;
						}
					}
					result.append('}');
					firstRow = false;
				}
				result.append(']');
				if (callback != null) {
					result.append(");");
				}

				response.status(200); // 200 ok

				if (DEBUG) System.out.println(result.toString() + "\n");

			} catch (SQLException ex) {
				response.status(503); // 503 Service Unavailable
				logger.log(Level.SEVERE, ex.getMessage(), ex);
//			} catch (Exception e) {
//				response.status(503); // 503 Service Unavailable
			} finally {
				try {
					if (rs != null) {
						rs.close();
					}
					if (st != null) {
						st.close();
					}
					if (con != null) {
						con.close();
					}

				} catch (SQLException ex) {
					logger.log(Level.WARNING, ex.getMessage(), ex);
				}
			}

			return result.toString();
		 }
      });


	  /////////////////// create new game table ///////////////////

      put(new Route("/lb/:gameID") {
         @Override
         public Object handle(Request request, Response response) {
			String callback = request.queryParams("callback");

			String gameID = toSafeString(request.params(":gameID"), 30);
			if (DEBUG) System.out.println(gameID);

			// read other parameters
			String content = request.body();
			JsonObject requestObj = JsonObject.readFrom(content);

			int maxEntries = Integer.parseInt(requestObj.get("maxEntries").asString());
			if (DEBUG) System.out.println(maxEntries);
			boolean onlyKeepBestEntry = Boolean.parseBoolean(requestObj.get("onlyKeepBestEntry").asString());
			if (DEBUG) System.out.println(onlyKeepBestEntry);
			String socialnetwork = requestObj.get("socialnetwork").asString();
			if (DEBUG) System.out.println(socialnetwork);

			String highScoreNames = "highscore int(11) DEFAULT NULL, ";
			JsonArray scoresArr = requestObj.get("highScoreNames").asArray();
			//JsonArray scoresArr = requestObj.getJSONArray("highScoreNames");
			for (int i=0; i<scoresArr.size(); i++) {
				String s = scoresArr.get(i).asString();
				if (!s.isEmpty()) highScoreNames += toSafeString(s, 30) + " int(11) DEFAULT NULL, ";
			}
			if (DEBUG) System.out.println(highScoreNames);

			Connection con = null;
			Statement st = null;

			response.type("application/json");

			StringBuilder result = new StringBuilder(40);
			if (callback != null) {
				result.append(callback);
				result.append("(");
			}

			try {
				con = DriverManager.getConnection(settings.url, settings.user, settings.password);
				st = con.createStatement();

				String updstr = "CREATE TABLE mygame." + gameID + " (playerID varchar(20) DEFAULT NULL" + (onlyKeepBestEntry?" UNIQUE KEY":"") + ", " + highScoreNames + "userData blob);";
				if (DEBUG) System.out.println(updstr + "\n");
				st.executeUpdate(updstr);

				//// add entry to $options
				PreparedStatement pst = con.prepareStatement("INSERT INTO mygame.$options (game, maxEntries, onlyKeepBestEntry, socialnetwork) VALUES (?, ?, ?, ?);");
				pst.setString(1, gameID);
				pst.setInt(2, maxEntries);
				pst.setInt(3, onlyKeepBestEntry?1:0 );
				pst.setString(4, socialnetwork);
				pst.executeUpdate();
				//// end add entry to $options

				response.status(200); // 200 ok
			} catch (SQLException ex) {
				response.status(503); // 503 Service Unavailable
				logger.log(Level.SEVERE, ex.getMessage(), ex);
//			} catch (Exception e) {
//				response.status(503); // 503 Service Unavailable
			} finally {
				try {
					if (st != null) {
						st.close();
					}
					if (con != null) {
						con.close();
					}

				} catch (SQLException ex) {
					logger.log(Level.WARNING, ex.getMessage(), ex);
				}
			}

			return "";
		 }
      });


	  /////////////////// delete game table ///////////////////

      delete(new Route("/lb/:gameID") {
         @Override
         public Object handle(Request request, Response response) {
			String callback = request.queryParams("callback");

			String gameID = toSafeString(request.params(":gameID"), 30);
			if (DEBUG) System.out.println(gameID);

			Connection con = null;
			Statement st = null;

			response.type("application/json");

			StringBuilder result = new StringBuilder(40);
			if (callback != null) {
				result.append(callback);
				result.append("(");
			}

			try {
				con = DriverManager.getConnection(settings.url, settings.user, settings.password);

				st = con.createStatement();

				String updstr = "DROP TABLE mygame." + gameID;
				if (DEBUG) System.out.println(updstr + "\n");

				st.executeUpdate(updstr);


				//// delete entry from $options
				st = con.createStatement();
				updstr = "DELETE FROM mygame.$options where game = \'" + gameID + "\';";
				if (DEBUG) System.out.println(updstr + "\n");
				st.executeUpdate(updstr);
				//// end delete from $options

				response.status(200); // 200 ok
			} catch (SQLException ex) {
				response.status(503); // 503 Service Unavailable
				logger.log(Level.SEVERE, ex.getMessage(), ex);
//			} catch (Exception e) {
//				response.status(503); // 503 Service Unavailable
			} finally {
				try {
					if (st != null) {
						st.close();
					}
					if (con != null) {
						con.close();
					}
				} catch (SQLException ex) {
					logger.log(Level.WARNING, ex.getMessage(), ex);
				}
			}

			return "";
		 }
      });


  } // main
}
