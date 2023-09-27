import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.rmi.RemoteException;

public class Server implements ProjectLib.CommitServing, ProjectLib.MessageHandling {
    static ProjectLib PL;
    private static final int PREPARE = 1;
    private static final int COMMIT = 2;
    private static final int ABORT = -1;
    private static final int ACK = 3;
    private static final long TIMEOUT = 6000;

    //commitMap records the number of commmits for each collage
    public static ConcurrentHashMap<String, Integer> commitMap = new ConcurrentHashMap<String, Integer>();
    //abortMap records the number of aborts for each collage
    public static ConcurrentHashMap<String, Integer>  abortMap = new ConcurrentHashMap<String, Integer>();
    //ack
    public static ConcurrentHashMap<String, Integer> ackMap = new ConcurrentHashMap<String, Integer>();
    //sourceList maps collage filename to an array of all the sources that it uses and its correspond usernode
    public static ConcurrentHashMap<String, String[]> sourceList = new ConcurrentHashMap<String, String[]>();
    

    /**
     * @brief serialize() serializes an object into a byte array so that it can 
     * be sent to the server
     * @param obj takes an Object
     * @return outputs serialized object in form of byte array
     */
    public static byte[] serialize(Object obj) throws IOException{
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ObjectOutputStream objectOutputStream = new ObjectOutputStream(outputStream);
        objectOutputStream.writeObject(obj);
        byte[] output = outputStream.toByteArray();
        return output;
    }

    /**
     * @brief deserialize() deserializes a byte array into an object
     * @param input takes a byte[]
     * @return returns object from byte[] input
     */
    public static Object deserialize(byte[] input) throws IOException, ClassNotFoundException {
        ByteArrayInputStream inputStream = new ByteArrayInputStream(input);
        ObjectInputStream objectInputStream = new ObjectInputStream(inputStream);
        Object obj = objectInputStream.readObject();
        return obj;
    }

    /**
     * deliverMessage() receive messages from the userNode and records responses
     * in corresponding HashMap (commits, aborts, acks)
     * @param msg takes ProjectLib.message
     * @return returns boolean indicating that message was received correctly
     */
    public boolean deliverMessage( ProjectLib.Message msg) {
        System.out.println("Server: Got message from " + msg.addr);
        try { 
            messageWrapper msgwrap = (messageWrapper)deserialize(msg.body);
            int count;
            switch(msgwrap.opcode) {
                case COMMIT:
                    commitMap.put(msgwrap.filename, commitMap.get(msgwrap.filename) + 1);
                    break;
                case ABORT:
                    abortMap.put(msgwrap.filename, abortMap.get(msgwrap.filename) + 1);
                    break;
                case ACK:
                    if (!ackMap.containsKey(msgwrap.filename)) {
                        ackMap.put(msgwrap.filename, ackMap.get(msgwrap.filename) + 1);
                    }
                    break;
            }
        }
        catch(IOException e) {
            e.printStackTrace();
        }
        catch(ClassNotFoundException e) {
            e.printStackTrace();
        }
        return true;
    }
	
    /**
     * @brief 2 phase commit routine communicates with userNode to decide 
     * whether or not to commit collage
     * @param filename takes String indicating name of collage
     * @param img takes byte[], a serialized form of the image
     * @param sources takes String[], list of usernodes and sources (user:source)
     */
    public void startCommit( String filename, byte[] img, String[] sources ) {
        HashMap<String, ArrayList<String>> sourceMap = new HashMap<String, ArrayList<String>>(); 
        messageWrapper msgwrap;
        ProjectLib.Message msg;
        int decision = ABORT;
        initMaps(filename);
        mapSources(sources, sourceMap, filename);

        //log state before sending out prepare////
        state serverState = new state(filename, decision, sourceMap, ackMap, sourceList, img);
        logState(serverState);
        
        //send collage to all usernodes and ask for vote        
        int numNodes = sourceMap.keySet().size();
        for (String node : sourceMap.keySet()) {
            ArrayList<String> srcMap = sourceMap.get(node);
            String[] srcArr = Arrays.copyOf(srcMap.toArray(), srcMap.size(), String[].class);    
            msgwrap = new messageWrapper(PREPARE, img, node, srcArr, filename);
            try {
                msg = new ProjectLib.Message(node, serialize(msgwrap));
                PL.sendMessage(msg);
            }
            catch(IOException e) {
                e.printStackTrace();
            }
        }        
        //wait for votes from user nodes
        long time = System.currentTimeMillis();
        decision = COMMIT;
        while (commitMap.get(filename) < numNodes) {
            //if wait time exceeds timeout value, then abort
            if (System.currentTimeMillis() - time > TIMEOUT) {
                decision = ABORT;
                break;
            }
            //if at least one user aborts, then server decision is to abort
            if (abortMap.get(filename) > 0) {
                decision = ABORT;
                break;
            }
        }
        serverState = new state(filename, decision, sourceMap, ackMap, sourceList, img);
        logState(serverState);
        if (decision == COMMIT) {
            commitCollage(img, filename);
        }
        //send decision to all userNodes
        broadcastDecision(decision, filename, sourceMap);
        //wait for acks from all userNodes
        long time2 = System.currentTimeMillis();
        //loops until all acknowledgements are received
        while (ackMap.size() < numNodes) {
            //if wait time exceeds timeout value, then resend server decision
            if (System.currentTimeMillis() - time2 > TIMEOUT) { 
                broadcastDecision(decision, filename, sourceMap);
                time2 = System.currentTimeMillis();
            }
        }        
    }
    
    /**
     * @brief commitCollage() takes serialized image and filename and writes 
     * collage data into a file using RandomAccessFile
     * @param img takes byte[], a serialized form of the image
     * @param filename takes String indicating name of collage
     */
    public static void commitCollage(byte[] img, String filename) {
        try {
            RandomAccessFile raf = new RandomAccessFile(filename, "rw");
            raf.write(img);
            raf.close();
        }
        catch(IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * @brief broadcastDecision() sends out the server decision along with other
     * metadata to all userNodes
     * @param decision takes int indicating abort or commit
     * @param filename takes String indicating name of collage
     * @param sources takes HashMap of nodes mapped to their sources
     */
    public static void broadcastDecision(int decision, String filename, HashMap<String, ArrayList<String>> sourceMap) {
        messageWrapper msgwrap;
        ProjectLib.Message broadcast;
        for (String node : sourceMap.keySet()) {
            ArrayList<String> srcMap = sourceMap.get(node);
            String[] srcArr = Arrays.copyOf(srcMap.toArray(), srcMap.size(), String[].class);    
            try {
                msgwrap = new messageWrapper(decision, null, node, srcArr, filename);
                broadcast = new ProjectLib.Message(node, serialize(msgwrap));
                PL.sendMessage(broadcast);
            }
            catch(IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * mapSources() parses the String array of usernodes and sources and adds it
     * to a hashmap mapping usernodes to its respective sources. It also maps 
     * collage filename to the unparsed String array of soures.
     * @param sources takes String[] 
     * @param sourceMap takes empty HashMap of Strings to ArrayList<String>
     * @param file takes String indicating name of collage
     */
    public void mapSources(String[] sources, HashMap<String, ArrayList<String>> sourceMap, String file) {
        sourceList.put(file, sources);
        for (String source : sources) {
            String src[] = source.split(":");
            String addr = src[0];
            String filename = src[1];
            if (!sourceMap.containsKey(addr)) {
                ArrayList<String> srcArr = new ArrayList<String>();
                srcArr.add(filename);
                sourceMap.put(addr, srcArr);
            }
            else {
                ArrayList<String> arr = sourceMap.get(addr);
                arr.add(filename);
                sourceMap.put(addr, arr);
            }
        }
    }

    /**
     * initMaps() initializes HashMaps that record the number of commits, aborts,
     * and acknowledgements for each collage to 0
     * @param collageName takes String
     */
    public void initMaps(String collageName) {
        commitMap.put(collageName, 0);
        abortMap.put(collageName, 0);
        ackMap.put(collageName, 0);
    }
    
    /**
     * logState() takes an object containing metadata and logs it using an 
     * objectOutputStream, fileOutputStream, and the Project lib function fsync().
     * @param obj takes Object containing metadata to be logged
     */
    public void logState(Object state) {
        try {
            RandomAccessFile raf = new RandomAccessFile("serverState.log", "rw");
            FileOutputStream fileOutputStr = new FileOutputStream(raf.getFD());
            ObjectOutputStream objOutputStr = new ObjectOutputStream(fileOutputStr);
            objOutputStr.writeObject(state);
            objOutputStr.close();
            raf.close();
            PL.fsync();
        }
        catch(IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * @brief recovery routine when userNode crashes. Reads log and resumes 
     * from where it crashed based on the phase it was in.
     */
    public static void recoverState() {
        try {
            FileInputStream fileInputStr = new FileInputStream("serverState.log");
            ObjectInputStream objInputStr = new ObjectInputStream(fileInputStr);
            state serverState = (state)objInputStr.readObject();
            //information from before crash is retreived from log file
            String filename = serverState.filename;
            int decision = serverState.decision;
            byte[] img = serverState.img;
            HashMap<String, ArrayList<String>> sourceMap = serverState.sourceMap;
            ackMap = serverState.ackMap;    
            int numNodes = sourceMap.keySet().size();    
            if (decision == COMMIT) {
                commitCollage(img, filename);
            }
            //send decision to all userNodes
            broadcastDecision(decision, filename, sourceMap);
            //wait for acks from all userNode
            long time2 = System.currentTimeMillis();
            while (ackMap.size() < numNodes) {
                //if waiting time exceeds 6 seconds, resend decision
                if (System.currentTimeMillis() - time2 > TIMEOUT) {
                    broadcastDecision(decision, filename, sourceMap);
                    time2 = System.currentTimeMillis();
                }
            }    
        }
        catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        catch (IOException e) {
            e.printStackTrace();
        }
        catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    public static void main ( String args[] ) throws Exception {
        if (args.length != 1) throw new Exception("Need 1 arg: <port>");
        Server srv = new Server();
        PL = new ProjectLib( Integer.parseInt(args[0]), srv, srv);
        File file = new File("serverState.log");
        if (file.exists()) {
            recoverState();
        }
        // main loop
        while (true) {
            ProjectLib.Message msg = PL.getMessage();
        }
    }
}



