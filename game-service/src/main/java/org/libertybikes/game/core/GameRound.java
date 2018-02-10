package org.libertybikes.game.core;

import static org.libertybikes.game.round.service.GameRoundWebsocket.sendTextToClient;
import static org.libertybikes.game.round.service.GameRoundWebsocket.sendTextToClients;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.websocket.Session;

import org.libertybikes.game.core.ClientMessage.GameEvent;
import org.libertybikes.game.core.Player.STATUS;

public class GameRound implements Runnable {

    public static enum State {
        OPEN, FULL, RUNNING, FINISHED
    }

    public static final int GAME_TICK_SPEED = 50; // ms
    public static final int BOARD_SIZE = 121;

    private static final Random r = new Random();

    public final String id;
    public final String nextRoundId;

    public final Map<Session, Client> clients = new HashMap<>();
    public State state = State.OPEN;

    private boolean[][] board = new boolean[BOARD_SIZE][BOARD_SIZE];
    private AtomicBoolean gameRunning = new AtomicBoolean(false);
    private AtomicBoolean paused = new AtomicBoolean(false);

    // Get a string of 6 random uppercase letters (A-Z)
    private static String getRandomId() {
        char[] chars = new char[6];
        for (int i = 0; i < 6; i++)
            chars[i] = (char) (r.nextInt(26) + 65);
        return new String(chars);
    }

    public GameRound() {
        this(getRandomId());
    }

    public GameRound(String id) {
        this.id = id;
        nextRoundId = getRandomId();
    }

    public void handleMessage(ClientMessage msg, Session client) {
        if (msg.event != null) {
            if (GameEvent.GAME_START == msg.event)
                startGame();
            else if (GameEvent.GAME_PAUSE == msg.event)
                pause();
        }

        if (msg.direction != null) {
            Player p = clients.get(client).player;
            p.setDirection(msg.direction);
        }

        if (msg.playerJoinedId != null) {
            addPlayer(client, msg.playerJoinedId);
        }

        if (Boolean.TRUE == msg.isSpectator) {
            addSpectator(client);
        }
    }

    public void addPlayer(Session s, String playerId) {
        Player p = PlayerFactory.initNextPlayer(this, playerId);
        clients.put(s, new Client(s, p));
        System.out.println("Player " + getPlayers().size() + " has joined.");
        broadcastPlayerList();
        broadcastPlayerLocations();
    }

    public void addSpectator(Session s) {
        System.out.println("A spectator has joined.");
        clients.put(s, new Client(s));
        sendTextToClient(s, getPlayerList());
        sendTextToClient(s, getPlayerLocations());
    }

    private void removePlayer(Player p) {
        p.disconnect();
        System.out.println(p.playerName + " disconnected.");
        broadcastPlayerList();
    }

    public int removeClient(Session client) {
        Client c = clients.remove(client);
        if (c != null && c.player != null)
            removePlayer(c.player);
        return clients.size();
    }

    public Set<Player> getPlayers() {
        return clients.values()
                        .stream()
                        .filter(c -> c.isPlayer())
                        .map(c -> c.player)
                        .collect(Collectors.toSet());
    }

    @Override
    public void run() {
        for (int i = 0; i < BOARD_SIZE; i++)
            Arrays.fill(board[i], true);
        gameRunning.set(true);
        System.out.println("Starting round: " + id);

        while (gameRunning.get()) {
            while (!paused.get()) {
                delay(GAME_TICK_SPEED);
                gameTick();
            }
            delay(500); // don't thrash when game is paused
        }
        System.out.println("Finished round: " + id);
    }

    private void gameTick() {
        // Move all living players forward 1
        boolean playerStatusChange = false;
        boolean playersMoved = false;
        for (Player p : getPlayers()) {
            if (p.isAlive) {
                if (p.movePlayer(board)) {
                    playersMoved = true;
                } else {
                    // Since someone died, check for winning player
                    checkForWinner(p);
                    playerStatusChange = true;
                }
            }
        }

        if (playersMoved)
            broadcastPlayerLocations();
        if (playerStatusChange)
            broadcastPlayerList();
    }

    private void delay(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
        }
    }

    private String getPlayerLocations() {
        JsonArrayBuilder arr = Json.createArrayBuilder();
        for (Player p : getPlayers())
            arr.add(p.toJson());
        return Json.createObjectBuilder().add("playerlocs", arr).build().toString();
    }

    private String getPlayerList() {
        JsonArrayBuilder array = Json.createArrayBuilder();
        for (Player p : getPlayers()) {
            array.add(Json.createObjectBuilder()
                            .add("name", p.playerName)
                            .add("status", p.getStatus().toString())
                            .add("color", p.color));
        }
        return Json.createObjectBuilder().add("playerlist", array).build().toString();
    }

    private void broadcastPlayerLocations() {
        sendTextToClients(clients.keySet(), getPlayerLocations());
    }

    private void broadcastPlayerList() {
        sendTextToClients(clients.keySet(), getPlayerList());
    }

    private void checkForWinner(Player dead) {
        if (getPlayers().size() < 2) // 1 player game, no winner
            return;
        int alivePlayers = 0;
        Player alive = null;
        for (Player cur : getPlayers()) {
            if (cur.isAlive) {
                alivePlayers++;
                alive = cur;
            }
        }
        if (alivePlayers == 1)
            alive.setStatus(STATUS.Winner);
    }

    public void startGame() {
        paused.set(false);
        for (Player p : getPlayers())
            if (STATUS.Connected == p.getStatus())
                p.setStatus(STATUS.Alive);
        broadcastPlayerList();
        if (!gameRunning.get()) {
            try {
                ExecutorService exec = InitialContext.doLookup("java:comp/DefaultManagedExecutorService");
                exec.submit(this);
            } catch (NamingException e) {
                System.out.println("Unable to start game due to: " + e);
                e.printStackTrace();
            }
        }
    }

    public void pause() {
        paused.set(true);
    }

    public void stopGame() {
        gameRunning.set(false);
    }

}
