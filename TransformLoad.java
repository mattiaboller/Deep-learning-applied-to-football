package com.company;

import org.json.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.sql.*;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Set;

//Script for uploading one championship at time in the database
public class Main {

    public static void main(String[] args) {
        Connection c = connectToDatabase();
        if(c!=null) {
            System.out.println("Connesso al database");

            File folder = new File(".\\data\\WhoScored\\Premier League"); //Change this path to upload different championship
            File[] listOfFiles = folder.listFiles();

            //Read every json file (match)
            for (File file : listOfFiles) {
                if (file.isFile()) {
                    try {
                        BufferedReader br = new BufferedReader(new FileReader(file));
                        String allFile = br.readLine();
                        allFile = allFile.substring(0, allFile.length() - 1);
                        parseJson(allFile, c);
                    } catch (Exception e) {
                        e.printStackTrace();
                        System.err.println(e.getClass().getName()+": "+e.getMessage());
                    }
                }
            }
        } else {
            System.out.println("Non connesso");
        }
    }

    static void parseJson(String file, Connection c){
        JSONObject json = new JSONObject(file);
        String score = json.getString("score");
        String date = json.getString("startDate").substring(0, 10);
        JSONObject home = json.getJSONObject("home");
        JSONObject away = json.getJSONObject("away");
        int homeTeamId = home.getInt("teamId");
        String homeTeamName = home.getString("name");
        int awayTeamId = away.getInt("teamId");
        String awayTeamName = away.getString("name");
        int championship = 4;

        //ADD TEAMS TO TEAMS TABLE
        addTeam(c, homeTeamId, homeTeamName, championship);
        addTeam(c, awayTeamId, awayTeamName, championship);

        //ADD MATCH TO MATCHES TABLE (GET MATCH ID)
        int matchId = addMatch(c, homeTeamId, awayTeamId, score, date, championship);

        //Parsing home team players (with ratings)
        JSONArray players = home.getJSONArray("players");
        for (int i=0; i<players.length(); i++) {
            JSONObject player = players.getJSONObject(i);
            if(player.getJSONObject("stats").length()!=0){
                String playerName = player.getString("name");
                int playerId = player.getInt("playerId");
                int shirtNo = player.getInt("shirtNo");
                int height = player.getInt("height");
                int weight = player.getInt("weight");
                int age = player.getInt("age");
                String pos = player.getString("position");
                int teamId = homeTeamId;
                boolean isFirstEleven;
                if(player.has("isFirstEleven"))
                    isFirstEleven=true;
                else
                    isFirstEleven=false;
                boolean isManOfTheMatch = player.getBoolean("isManOfTheMatch");
                String field = "home";

                //Matrix with minute and rating
                JSONObject ratings = player.getJSONObject("stats").getJSONObject("ratings");
                Set<String> keys = ratings.keySet();
                double[][] rating = new double[keys.size()][2];
                int j=0;
                for (String key:keys) {
                    rating[j][0] = Double.parseDouble(key);
                    rating[j][1] = ratings.getDouble(key);
                    j++;
                }
                Arrays.sort(rating, (a, b) -> Double.compare(a[0], b[0]));

                //ADD PLAYER TO PLAYERS TABLE
                addPlayer(c, playerId, playerName, shirtNo, height, weight, age, teamId);

                //ADD RATING TO RATINGS TABLE
                addRating(c, playerId, matchId, isFirstEleven, isManOfTheMatch, field, rating, teamId, pos);
            }
        }

        //Parsing away team players (with ratings)
        players = away.getJSONArray("players");
        for (int i=0; i<players.length(); i++) {
            JSONObject player = players.getJSONObject(i);
            if(player.getJSONObject("stats").length()!=0){
                String playerName = player.getString("name");
                int playerId = player.getInt("playerId");
                int shirtNo = player.getInt("shirtNo");
                int height = player.getInt("height");
                int weight = player.getInt("weight");
                int age = player.getInt("age");
                String pos = player.getString("position");
                int teamId = awayTeamId;
                boolean isFirstEleven;
                if(player.has("isFirstEleven"))
                    isFirstEleven=true;
                else
                    isFirstEleven=false;
                boolean isManOfTheMatch = player.getBoolean("isManOfTheMatch");
                String field = "away";

                //Matrix with minute and rating
                JSONObject ratings = player.getJSONObject("stats").getJSONObject("ratings");
                Set<String> keys = ratings.keySet();
                double[][] rating = new double[keys.size()][2];
                int j=0;
                for (String key:keys) {
                    rating[j][0] = Double.parseDouble(key);
                    rating[j][1] = ratings.getDouble(key);
                    j++;
                }
                Arrays.sort(rating, (a, b) -> Double.compare(a[0], b[0]));
                
                //ADD PLAYER TO PLAYERS TABLE
                addPlayer(c, playerId, playerName, shirtNo, height, weight, age, teamId);

                //ADD RATING TO RATINGS TABLE
                addRating(c, playerId, matchId, isFirstEleven, isManOfTheMatch, field, rating, teamId, pos);
            }
        }

        //Parse events
        JSONArray events = json.getJSONArray("events");
        for (int i=0; i<events.length(); i++) {
            JSONObject event = events.getJSONObject(i);
            if(event.has("playerId")) {
                int teamId = event.getInt("teamId");
                int playerId = event.getInt("playerId");
                int minute=event.getInt("minute");
                int second;
                if(event.has("second"))
                    second=event.getInt("second");
                else
                  second=0;
                int period = event.getJSONObject("period").getInt("value");
                String type = event.getJSONObject("type").getString("displayName");
                String outcomeType = event.getJSONObject("outcomeType").getString("displayName");
                if(type.equals("Card")){
                    outcomeType = event.getJSONObject("cardType").getString("displayName");
                }

                //ADD EVENT TO EVENTS TABLE
                addEvent(c, playerId, matchId, minute, second, period, type, outcomeType, teamId);
            }
        }
    }

    static Connection connectToDatabase(){
        Connection c = null;
        try {
            Class.forName("org.postgresql.Driver");
            c = DriverManager
                    .getConnection("jdbc:postgresql://localhost:5432/WhoScored",
                            "postgres", "admin"); //Connection to the database
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println(e.getClass().getName()+": "+e.getMessage());
        }

        return c;
    }

    static void addTeam(Connection c, int teamId, String name, int championshipId){
        String query = "INSERT INTO teams(team_id, name, championship) " + "VALUES(?,?,?)";
        try {
            PreparedStatement pstmt = c.prepareStatement(query);
            pstmt.setInt(1, teamId);
            pstmt.setString(2, name);
            pstmt.setInt(3, championshipId);
            pstmt.executeUpdate();
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println(e.getClass().getName()+": "+e.getMessage());
        }
    }

    static int addMatch(Connection c, int homeTeam, int awayTeam, String score, String date, int championshipId) {
        int matchId=0;
        DateFormat format = new SimpleDateFormat("yyyy-MM-dd");
        Date matchDate=null;
        try {
            java.util.Date utilStartDate = format.parse(date);
            matchDate =  new Date(utilStartDate.getTime());
        } catch (ParseException e) { }
        String query = "INSERT INTO matches(homeTeam, awayTeam, score, matchDate, championship) " + "VALUES(?,?,?,?,?)";
        try {
            PreparedStatement pstmt = c.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
            pstmt.setInt(1, homeTeam);
            pstmt.setInt(2, awayTeam);
            pstmt.setString(3, score);
            pstmt.setDate(4, matchDate);
            pstmt.setInt(5, championshipId);
            int affectedRows = pstmt.executeUpdate();
            // check the affected rows
            if (affectedRows > 0) {
                // get the ID back
                try (ResultSet rs = pstmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        matchId = rs.getInt(1);
                    }
                } catch (SQLException ex) {
                    System.out.println(ex.getMessage());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println(e.getClass().getName()+": "+e.getMessage());
        }
        return matchId;
    }

    static void addPlayer(Connection c, int playerId, String name, int shirtNo, int height, int weight, int age, int team){
        String query = "INSERT INTO players(player_id, name, shirtnum, height, weight, age, team) " + "VALUES(?,?,?,?,?,?,?)";
        try {
            PreparedStatement pstmt = c.prepareStatement(query);
            pstmt.setInt(1, playerId);
            pstmt.setString(2, name);
            pstmt.setInt(3, shirtNo);
            pstmt.setInt(4, height);
            pstmt.setInt(5, weight);
            pstmt.setInt(6, age);
            pstmt.setInt(7, team);
            pstmt.executeUpdate();
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println(e.getClass().getName()+": "+e.getMessage());
        }
    }

    static void addRating(Connection c, int playerId, int matchId, boolean isFirstEleven, boolean isManOfTheMatch, String field, double[][] ratings, int teamId, String pos){
        String query = "INSERT INTO ratings(player_id, team_id, match_id, isfirsteleven, ismanofthematch, field, ratings, position) " + "VALUES(?,?,?,?,?,?,?,?)";
        try {
            PreparedStatement pstmt = c.prepareStatement(query);
            pstmt.setInt(1, playerId);
            pstmt.setInt(2, teamId);
            pstmt.setInt(3, matchId);
            pstmt.setBoolean(4, isFirstEleven);
            pstmt.setBoolean(5, isManOfTheMatch);
            pstmt.setString(6, field);
            Array rat = c.createArrayOf("float8", ratings);
            pstmt.setArray(7, rat);
            pstmt.setString(8, pos);
            pstmt.executeUpdate();
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println(e.getClass().getName()+": "+e.getMessage());
        }
    }

    static void addEvent(Connection c, int playerId, int matchId, int minute, int second, int eventPeriod, String eventType, String eventOutcomeType, int teamId){
        String query = "INSERT INTO events(player_id, match_id, team_id, minute, second, eventperiod, eventtype, eventoutcometype) " + "VALUES(?,?,?,?,?,?,?,?)";
        try {
            PreparedStatement pstmt = c.prepareStatement(query);
            pstmt.setInt(1, playerId);
            pstmt.setInt(2, matchId);
            pstmt.setInt(3, teamId);
            pstmt.setInt(4, minute);
            pstmt.setInt(5, second);
            pstmt.setInt(6, eventPeriod);
            pstmt.setString(7, eventType);
            pstmt.setString(8, eventOutcomeType);
            pstmt.executeUpdate();
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println(e.getClass().getName()+": "+e.getMessage());
        }
    }
}
