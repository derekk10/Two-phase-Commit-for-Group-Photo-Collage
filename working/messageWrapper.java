import java.io.*;
import java.util.*;
import java.rmi.RemoteException;
class messageWrapper implements Serializable {
    int opcode;
    String addr;
    String[] sources;
    ProjectLib.Message msg;
	String filename;
    public messageWrapper(int opcode, byte[] body, String addr, String[] sources, String filename) {
        msg = new ProjectLib.Message(addr, body);
        this.opcode = opcode;
        this.addr = addr;
        this.msg = msg;
        this.sources = sources;
		this.filename = filename;
    }

	//dont need
    public static byte[] serialize(Object obj) throws IOException
	{
    	ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    	ObjectOutputStream objectOutputStream = new ObjectOutputStream(outputStream);
    	objectOutputStream.writeObject(obj);
		byte[] output = outputStream.toByteArray();
    	return output;
	}

	public static Object deserialize(byte[] input) throws IOException, ClassNotFoundException 
	{
    	ByteArrayInputStream inputStream = new ByteArrayInputStream(input);
    	ObjectInputStream objectInputStream = new ObjectInputStream(inputStream);
		Object obj = objectInputStream.readObject();
    	return obj;
	}
}