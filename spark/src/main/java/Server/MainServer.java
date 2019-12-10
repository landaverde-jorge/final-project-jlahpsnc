package Server;

import static spark.Spark.*;

import DAO.GameStateDao;
import DAO.PlayerDao;
import DTO.GameStateDto;
import DTO.PlayerDto;
import WebSocket.WebSocketHandler;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import javafx.util.Pair;
import spark.Request;
import spark.Response;
import org.eclipse.jetty.websocket.api.Session;

import java.util.*;

public class MainServer {
   // List of current players waiting in queue
   static ArrayList<Pair<PlayerDto, Session>> queueList = new ArrayList<>();

   //List of current games going on
   static ArrayList<GameStateDto> gameList = new ArrayList<>();
   static int totalGames = 0;

   public static Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public static void main(String[] args) {
    port(1234);

    webSocket("/wsLoading", WebSocketHandler.class);

    post("/login", MainServer::logIn);

    post("/register", MainServer::register);

    get("/playerInfo", MainServer::playerInfo);

    get("/rankings", MainServer::rankings);
    }

    private static String logIn(Request request, Response response) {
        String username = request.queryMap("username").value();
        String password = request.queryMap("password").value();
        if (request.queryParams().size() == 2 && username != null && password != null) {
            PlayerDto receivedPlayer = PlayerDao.getInstance().getPlayerByUsername(username);
            if (receivedPlayer != null) {
                if (receivedPlayer.password.equals(password)) {
                    if (!receivedPlayer.isLoggedIn) {
                        PlayerDao.getInstance().updatePlayerLoggedStatusById(receivedPlayer._id, true);
                        WebSocket.Response messageToReturn = new WebSocket.Response("Login Success", receivedPlayer._id);
                        return gson.toJson(messageToReturn);
                    } else {
                        WebSocket.Response messageToReturn = new WebSocket.Response("Login Failed", "User is logged in");
                        return gson.toJson(messageToReturn);
                    }
                } else {
                    WebSocket.Response messageToReturn = new WebSocket.Response("Login Failed", "Invalid password");
                    return gson.toJson(messageToReturn);
                }
            } else {
                WebSocket.Response messageToReturn = new WebSocket.Response("Login Failed", "Invalid username");
                return gson.toJson(messageToReturn);
            }
        } else {
            WebSocket.Response messageToReturn = new WebSocket.Response("Login Failed", "Invalid query");
            return gson.toJson(messageToReturn);
        }
    }

    private static String register(Request request, Response response) {
        String username = request.queryMap("username").value();
        String password = request.queryMap("password").value();
        if (request.queryParams().size() == 2 && username != null && password != null) {
            String newUserId = PlayerDao.getInstance().addPlayerToDatabase(username, password);
            if (newUserId != null) {
                WebSocket.Response messageToReturn = new WebSocket.Response("Register Success", newUserId);
                return gson.toJson(messageToReturn);
            } else {
                WebSocket.Response messageToReturn = new WebSocket.Response("Register Failed", "Invalid username");
                return gson.toJson(messageToReturn);
            }
        } else {
            WebSocket.Response messageToReturn = new WebSocket.Response("Register Failed", "Invalid query");
            return gson.toJson(messageToReturn);
        }
    }

    private static String playerInfo(Request request, Response response) {
        String playerId = request.queryMap("playerId").value();
        if (request.queryParams().size() == 1 && playerId != null) {
            PlayerDto playerToReturn = PlayerDao.getInstance().getPlayerById(playerId);
            if (playerToReturn != null) {
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                WebSocket.Response messageToReturn = new WebSocket.Response("Player Info Success", gson.toJson(playerToReturn));
                return gson.toJson(messageToReturn);
            } else {
                WebSocket.Response messageToReturn = new WebSocket.Response("Player Info Failed", "Invalid ID");
                return gson.toJson(messageToReturn);
            }
        } else {
            WebSocket.Response messageToReturn = new WebSocket.Response("Player Info Failed", "Invalid query");
            return gson.toJson(messageToReturn);
        }
    }

    private static String rankings(Request request, Response response) {
        ArrayList<PlayerDto> allPlayers = PlayerDao.getInstance().getAllPlayers();
        Collections.sort(allPlayers);

        int size = 10;
        if (allPlayers.size() < size) {
            size = allPlayers.size();
        }

        PlayerDto[] topPlayers = allPlayers.subList(0, size).toArray(new PlayerDto[allPlayers.size()]);
        WebSocket.Response messageToReturn = new WebSocket.Response("Rankings Success", gson.toJson(topPlayers));
        return gson.toJson(messageToReturn);
    }

    // Adds a player to queue, given their ID and session
    public static void addPlayerToQueue(String playerId, Session session) {
      PlayerDto player = PlayerDao.getInstance().getPlayerById(playerId);
        if (player != null) {
            queueList.add(new Pair<>(player, session));
            PlayerDao.getInstance().updatePlayerGameStatusById(player._id, true, false);

            // After adding a player to the queue, attempt to match them with someone else in the queue
            if (queueList.size() == 2) {
                Pair<PlayerDto, Session> playerOne = queueList.remove(0);
                PlayerDao.getInstance().updatePlayerGameStatusById(playerOne.getKey()._id, false, false);
                Pair<PlayerDto, Session> playerTwo = queueList.remove(0);
                PlayerDao.getInstance().updatePlayerGameStatusById(playerTwo.getKey()._id, false, false);

                createGame(playerOne, playerTwo);
            }
        }
    }

    // Helper function to create a game, given two players and their sessions
    private static void createGame(Pair<PlayerDto, Session> playerOne, Pair<PlayerDto, Session> playerTwo) {
        GameStateDto newGame = new GameStateDto(generateNewGameId(), playerOne.getKey(), playerOne.getValue(),
                playerTwo.getKey(), playerTwo.getValue());

        gameList.add(newGame);
        PlayerDao.getInstance().updatePlayerGameStatusById(playerOne.getKey()._id, false, true);
        PlayerDao.getInstance().updatePlayerGameStatusById(playerTwo.getKey()._id, false, true);
        WebSocketHandler.newGameBroadcast(newGame);
    }

    // Returns an ongoing game from the game list, based on the gameId
    public static GameStateDto getGameById(int gameId) {
      for (GameStateDto game: gameList) {
          if (game.gameId == gameId) {
              return game;
          }
      }
      return null;
    }

    // Removes an ongoing game from the game list, based on the gameId
    public static boolean removeGameById(int gameId) {
        for (GameStateDto game: gameList) {
            if (game.gameId == gameId) {
                PlayerDao.getInstance().updatePlayerGameStatusById(game.playerOne._id, false, false);
                PlayerDao.getInstance().updatePlayerGameStatusById(game.playerTwo._id, false, false);

                /* TODO
                1. Update highscores (on MongoDB)
                2. Notify players that game has ended (via websocket)
                 */

                gameList.remove(game);
                return true;
            }
        }
        return false;
    }

    // Helper function to generate a new gameId, and update the count for the next game gameId
    private static int generateNewGameId() {
      totalGames++;
      return (totalGames);
    }

    public static void processMessage(String message, Session session) {
      WebSocket.Response response = gson.fromJson(message, WebSocket.Response.class);

      String responseType = response.responseType;
      switch (responseType) {
          case "Flip Card":
              flipCard(response.responseBody);
              break;

          case "Logout":
              logout(response.responseBody);
              break;

          case "Disconnected":
              userDisconnected(response.responseBody, session);
              break;

      }
    }

    /*
    Format of response body must be as follows: "gameId,playerId,x,y"
    ex: "5,9F1d5q7,3,7"
    */
    private static void flipCard(String flipInformation) {
        String[] splitString = flipInformation.split(",");
        if (splitString.length == 4) {
            int gameId = Integer.parseInt(splitString[0]);
            String playerId = splitString[1];
            int cardX = Integer.parseInt(splitString[2]);
            int cardY = Integer.parseInt(splitString[3]);

            GameStateDao.getInstance().flipCard(gameId, playerId, cardX, cardY);
        }
    }

    /*
    Format of response body must be as follows: "playerId"
    ex: "9F1d5q7"
    */
    private static String logout(String playerId) {
        PlayerDto player = PlayerDao.getInstance().getPlayerById(playerId);
        if (player != null) {
            if (player.isLoggedIn) {
                PlayerDao.getInstance().updatePlayerGameStatusById(player._id, false, false);
                PlayerDao.getInstance().updatePlayerLoggedStatusById(player._id, false);
                WebSocket.Response messageToReturn = new WebSocket.Response("Logout Success", player._id);
                return gson.toJson(messageToReturn);
            } else {
                WebSocket.Response messageToReturn = new WebSocket.Response("Logout Failed", "User is not logged in");
                return gson.toJson(messageToReturn);
            }
        } else {
            WebSocket.Response messageToReturn = new WebSocket.Response("Logout Failed", "Invalid ID");
            return gson.toJson(messageToReturn);
        }
    }

    public static void userDisconnected(String userId, Session session) {
      /* TODO
      1. Check if person is in queue, and remove them if so
      2. Check if person is in game, and end game if so
      3. Check if person is logged in, and log them out if so
       */
    }
}