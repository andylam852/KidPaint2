// java
import java.io.IOException;
import java.net.Socket;


public class WorkerThread extends Thread {
    String username;
    Socket socket;
    KidPaintServer server;

    public WorkerThread(KidPaintServer server, Socket socket) {
        this.socket = socket;
        this.server = server;
    }

    public void setUsername(String name){
        username = name;
    }

    public String getUsername(){
        return username;
    }

    @Override
    public void run(){
        try{
            server.serve(socket, this);
        }catch (IOException e){
            synchronized (server.clientList){
                server.clientList.remove(socket);
            }
            System.out.println("client disconnected");
        }
    }
}
