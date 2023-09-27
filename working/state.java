import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.rmi.RemoteException;
class state implements Serializable {
    String filename;
    int decision;
    HashMap<String, ArrayList<String>> sourceMap;
    ConcurrentHashMap<String, Integer> ackMap;
    ConcurrentHashMap<String, String[]> sourceList;
    String[] sources;
    int phase;
    byte[] img;

    //constructor for server state
    public state(String filename, int decision, HashMap<String, ArrayList<String>>
     sourceMap, ConcurrentHashMap<String, Integer> ackMap, 
     ConcurrentHashMap<String, String[]> sourceList, byte[] img) {
        this.filename = filename;
        this.decision = decision;
        this.sourceMap = sourceMap;
        this.ackMap = ackMap;
        this.sourceList = sourceList; 
        this.img = img;
    }
    //constructor for usernode state
    public state(String filename, int decision , String[] sources, int phase) {
        this.decision = decision;
        this.sources = sources;
        this.phase = phase;
    }
    

    
}