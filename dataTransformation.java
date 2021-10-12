package org.mattia.boller.deeplearning;

import java.io.File;
import java.io.FileWriter;
import java.math.BigDecimal;
import java.sql.*;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;

//Script for modelling data in a format that a neural network can use
public class App
{
    public static void main( String[] args ) {
        createCSV(); //Model data
        createPlayersListCSV(); //List of all players (useful for one-hot encoding)
    }

    static Connection connectToDatabase(){
        Connection c = null;
        try {
            Class.forName("org.postgresql.Driver");
            c = DriverManager
                    .getConnection("jdbc:postgresql://localhost:5432/WhoScored",
                            "postgres", "admin");
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println(e.getClass().getName()+": "+e.getMessage());
        }

        return c;
    }

    //ChampionshipId SerieA=1, PremierLeague=2, LaLiga=3, Bundeliga=4
    static ResultSet getPlayersList(Connection c){
        try {
            Statement st = c.createStatement();
            return st.executeQuery("SELECT distinct player_id FROM ratings inner join matches " +
                    "on ratings.match_id=matches.match_id " +
                    "where (ratings.ratings[array_length(ratings.ratings,1)-1][0] - ratings.ratings[0][0]) >= 45 AND championship=4"); //Change the championsip id to select another championship
        } catch (SQLException throwables) {
            throwables.printStackTrace();
            return null;
        }
    }

    //Select all the player performances of more than 45 minutes
    static ResultSet getRatings(Connection c) {
        try {
            Statement st = c.createStatement();
            return st.executeQuery("SELECT player_id, team_id, ratings.match_id, isfirsteleven, ismanofthematch, field, ratings, ratings.position FROM ratings inner join matches " +
                    "on ratings.match_id = matches.match_id " +
                    "where (ratings.ratings[array_length(ratings.ratings,1)-1][0] - ratings.ratings[0][0]) >= 45 AND championship=2 " + //Change the championsip id to select another championship
                    "order by ratings.match_id");
        } catch (SQLException throwables) {
            throwables.printStackTrace();
            return null;
        }
    }

    static ResultSet getEvents(Connection c, int playerId, int matchId, int startMin, int endMin){
        try {
            PreparedStatement st = c.prepareStatement("SELECT * FROM events WHERE player_id = ? AND match_id = ? AND (eventtype='Goal' OR " +
                    "eventtype='Card' OR eventtype='Foul' OR eventtype='Pass' OR eventtype='BallTouch' OR eventtype='BallRecovery' OR eventtype='Tackle' OR " +
                    "eventtype='TakeOn' OR eventtype='Aerial' OR eventtype='MissedShot' OR eventtype='SavedShot' OR eventtype='Save') AND (minute>=? AND minute<?)");
            st.setInt(1, playerId);
            st.setInt(2, matchId);
            st.setInt(3, startMin);
            st.setInt(4, endMin);
            return st.executeQuery();
        } catch (SQLException throwables) {
            throwables.printStackTrace();
            return null;
        }
    }

    static ResultSet getScoreChanges(Connection c, int matchId, int startMin, int endMin){
        try {
            PreparedStatement st = c.prepareStatement("SELECT * FROM events WHERE match_id = ? AND eventtype='Goal' AND (minute>=? AND minute<?)");
            st.setInt(1, matchId);
            st.setInt(2, startMin);
            st.setInt(3, endMin);
            return st.executeQuery();
        } catch (SQLException throwables) {
            throwables.printStackTrace();
            return null;
        }
    }

    //Create the CSV file for one championship at time, for change championship look at the getRatings() function
    static void createCSV(){
        Connection c = connectToDatabase();
        try {
            ResultSet allPerformances = getRatings(c); //Get all ratings in the database
            int fileName=0;
            String pathFeatures=".\\data\\Bundesliga\\features"; //Change the championship name to work with another championship
            String pathLabelsBinary=".\\data\\Bundesliga\\labelsBinary";
            String pathLabelsAVG=".\\data\\Bundesliga\\labelsAVG";
            while (allPerformances.next()) {
                String csv = "Rating, PlayerId, GoalScored, GoalTaken, Card, Foul, PassSuc, PassUnsuc, BallTouchSuc, BallTouchUnsuc, BallRecovery, TackleSuc, " +
                        "TackleUnsuc, TakeOnSuc, TakeOnUnsuc, AerialSuc, AerialUnsuc, Goal, MissedShot, SavedShot, Save\n"; //First line of the file
                int playerId = allPerformances.getInt("player_id");
                int matchId = allPerformances.getInt("match_id");
                int teamId = allPerformances.getInt("team_id");
                BigDecimal[][] ratings = (BigDecimal[][]) allPerformances.getArray("ratings").getArray();
                BigDecimal prevMinute=BigDecimal.valueOf(0);
                int i;
                double lastRating=0;

                //Get the 75% of the performance
                for(i=0; ratings[i][0].intValue()<((ratings[ratings.length-1][0].doubleValue()/100)*75); i++) {
                    BigDecimal currentMinute=ratings[i][0];
                    int GoalScored=0, GoalTaken=0, Card=0, Foul=0, PassSuc=0, PassUnsuc=0, BallTouchSuc=0, BallTouchUnsuc=0, BallRecovery=0, TackleSuc=0, TackleUnsuc=0,
                            TakeOnSuc=0, TakeOnUnsuc=0, AerialSuc=0, AerialUnsuc=0, Goal=0, MissedShot=0, SavedShot=0, Save=0;
                    lastRating = ratings[i][1].doubleValue();
                    csv = csv + String.valueOf(ratings[i][1]) + ", " + String.valueOf(playerId) + ", ";
                    if(prevMinute != currentMinute){
                        ResultSet scoreChanges = getScoreChanges(c, matchId, prevMinute.intValue(), currentMinute.intValue());
                        while(scoreChanges.next()){
                            //Count all the goals
                            if(teamId == scoreChanges.getInt("team_id"))
                                GoalScored++;
                            else
                                GoalTaken++;
                        }

                        csv = csv + GoalScored + ", " + GoalTaken + ", ";

                        //Count all the events
                        ResultSet events = getEvents(c, playerId, matchId, prevMinute.intValue(), currentMinute.intValue());
                        while(events.next()){
                            String eventType=events.getString("eventtype");
                            switch(eventType){
                                case "Card":
                                    Card++;
                                    break;

                                case "Foul":
                                    Foul++;
                                    break;

                                case "Pass":
                                    if(events.getString("eventoutcometype").equals("Successful"))
                                        PassSuc++;
                                    else
                                        PassUnsuc++;
                                    break;

                                case "BallTouch":
                                    if(events.getString("eventoutcometype").equals("Successful"))
                                        BallTouchSuc++;
                                    else
                                        BallTouchUnsuc++;
                                    break;

                                case "BallRecovery":
                                    BallRecovery++;
                                    break;

                                case "Tackle":
                                    if(events.getString("eventoutcometype").equals("Successful"))
                                        TackleSuc++;
                                    else
                                        TackleUnsuc++;
                                    break;

                                case "TakeOn":
                                    if(events.getString("eventoutcometype").equals("Successful"))
                                        TakeOnSuc++;
                                    else
                                        TakeOnUnsuc++;
                                    break;

                                case "Aerial":
                                    if(events.getString("eventoutcometype").equals("Successful"))
                                        AerialSuc++;
                                    else
                                        AerialUnsuc++;
                                    break;

                                case "Goal":
                                    Goal++;
                                    break;

                                case "MissedShot":
                                    MissedShot++;
                                    break;

                                case "SavedShot":
                                    SavedShot++;
                                    break;

                                case "Save":
                                    Save++;
                                    break;
                            }
                        }
                    }
                    prevMinute=currentMinute;
                    csv = csv + Card + ", " + Foul + ", " + PassSuc + ", " +  PassUnsuc + ", " + BallTouchSuc + ", " + BallTouchUnsuc + ", " +
                            BallRecovery + ", " + TackleSuc + ", " + TackleUnsuc + ", " + TakeOnSuc + ", " + TakeOnUnsuc + ", " +
                            AerialSuc + ", " + AerialUnsuc + ", " + Goal + ", " + MissedShot + ", " + SavedShot + ", " + Save + "\n";
                }

                //Write the file
                FileWriter file = new FileWriter(pathFeatures + File.separator + String.valueOf(fileName)+".csv");
                file.write(csv);
                file.close();

                //Count the average rating in the last 25% of the performance
                double avg=0;
                int count=0;
                while(i<ratings.length){
                    avg = avg + ratings[i][1].doubleValue();
                    count++;
                    i++;
                }
                avg=avg/count;
                DecimalFormat df = new DecimalFormat("0.00");
                DecimalFormatSymbols custom = new DecimalFormatSymbols();
                custom.setDecimalSeparator('.');
                df.setDecimalFormatSymbols(custom);
                String performance;
                //Check if better or worst
                if(avg>lastRating)
                    performance="1";
                else
                    performance="0";
                file = new FileWriter(pathLabelsBinary + File.separator + String.valueOf(fileName)+".csv");
                file.write(performance);
                file.close();
                file = new FileWriter(pathLabelsAVG + File.separator + String.valueOf(fileName)+".csv");
                file.write(String.valueOf(df.format(avg)));
                file.close();

                fileName++;
            }
        } catch (Exception e){
            e.printStackTrace();
            System.err.println(e.getClass().getName()+": "+e.getMessage());
        }
    }

    //Create the list of players
    static void createPlayersListCSV() {
        String pathPlayersList=".\\data\\Bundesliga\\playersList"; //Change the path for work with others championships
        ResultSet playersList = getPlayersList(connectToDatabase());
        String csv="";
        try {
            while (playersList.next()) {
                csv = csv + playersList.getString("player_id") + "\n";
            }
        } catch (Exception e){}
        csv = csv.substring(0, csv.length()-2);
        try {
            FileWriter file = new FileWriter(pathPlayersList + File.separator + "playersList.csv");
            file.write(csv);
            file.close();
        } catch (Exception e){}
    }
}
