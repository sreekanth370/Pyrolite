package net.razorvine.pyro;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.TreeMap;


/**
 * Pyro wire protocol message.
 */
public class Message
{
	private final static int MAGIC_NUMBER = 0x4dc5;
	public final static int HEADER_SIZE = 40;

	public final static byte MSG_CONNECT = 1;
	public final static byte MSG_CONNECTOK = 2;
	public final static byte MSG_CONNECTFAIL = 3;
	public final static byte MSG_INVOKE = 4;
	public final static byte MSG_RESULT = 5;
	public final static byte MSG_PING = 6;
	public final static int FLAGS_EXCEPTION = 1<<0;
	public final static int FLAGS_COMPRESSED = 1<<1;
	public final static int FLAGS_ONEWAY = 1<<2;
	public final static int FLAGS_BATCH = 1<<3;
	public final static int FLAGS_ITEMSTREAMRESULT = 1<<4;
	public final static int FLAGS_KEEPSERIALIZED = 1<<5;
	public final static int FLAGS_CORR_ID = 1<<6;
	public final static byte SERIALIZER_SERPENT = 1;
	public final static byte SERIALIZER_MARSHAL = 2;
	public final static byte SERIALIZER_JSON = 3;
	public final static byte SERIALIZER_MSGPACK = 4;

	public byte type;
	public int flags;
	public byte[] data;
	public int data_size;
	public int annotations_size;
	public byte serializer_id;
	public int seq;
	public SortedMap<String, byte[]> annotations;

	/**
	 * construct a header-type message, without data and annotations payload.
	 */
	public Message(byte msgType, byte serializer_id, int flags, int seq)
	{
		this.type = msgType;
		this.flags = flags;
		this.seq = seq;
		this.serializer_id = serializer_id;
	}

	/**
	 * construct a full wire message including data and annotations payloads.
	 */
	public Message(byte msgType, byte[] databytes, byte serializer_id, int flags, int seq, SortedMap<String, byte[]> annotations)
	{
		this(msgType, serializer_id, flags, seq);
		this.data = databytes;
		this.data_size = databytes.length;
		this.annotations = annotations;
		if(null==annotations)
			this.annotations = new TreeMap<String, byte[]>();

		this.annotations_size = 0;
		for(Entry<String, byte[]> a: this.annotations.entrySet())
			this.annotations_size += a.getValue().length+8;
	}

	/**
	 * creates a byte stream containing the header followed by annotations (if any) followed by the data
	 */
	public byte[] to_bytes()
	{
		byte[] header_bytes = get_header_bytes();
		byte[] annotations_bytes = get_annotations_bytes();
		byte[] result = new byte[header_bytes.length + annotations_bytes.length + data.length];
		System.arraycopy(header_bytes, 0, result, 0, header_bytes.length);
		System.arraycopy(annotations_bytes, 0, result, header_bytes.length, annotations_bytes.length);
		System.arraycopy(data, 0, result, header_bytes.length+annotations_bytes.length, data.length);
		return result;
	}

	public byte[] get_header_bytes()
	{
		byte[] header = new byte[HEADER_SIZE];
		/*
The header format is::

0x00   4s  4   'PYRO' (message identifier)
0x04   H   2   protocol version
0x06   B   1   message type
0x07   B   1   serializer id
0x08   H   2   message flags
0x0a   H   2   sequence number   (to identify proper request-reply sequencing)
0x0c   I   4   data length   (max 4 Gb)
0x10   I   4   annotations length (max 4 Gb, total of all chunks, 0 if no annotation chunks present)
0x14   16s 16  correlation uuid
0x24   H   2   (reserved)
0x26   H   2   magic number 0x4dc5
total size: 0x28 (40 bytes)

After the header, zero or more annotation chunks may follow, of the format::

   4s  4   annotation id (4 ASCII letters)
   I   4   chunk length  (max 4 Gb)
   B   x   annotation chunk databytes

After that, the actual payload data bytes follow.
		*/

		header[0]=(byte)'P';
		header[1]=(byte)'Y';
		header[2]=(byte)'R';
		header[3]=(byte)'O';

		header[4]=(byte) (Config.PROTOCOL_VERSION>>8);
		header[5]=(byte) (Config.PROTOCOL_VERSION&0xff);

		header[6]=type;
		header[7]=serializer_id;

		header[8]=(byte) (flags>>8);
		header[9]=(byte) (flags&0xff);

		header[10]=(byte)(seq>>8);
		header[11]=(byte)(seq&0xff);

		header[12]=(byte)((data_size>>24)&0xff);
		header[13]=(byte)((data_size>>16)&0xff);
		header[14]=(byte)((data_size>>8)&0xff);
		header[15]=(byte)(data_size&0xff);

		header[16]=(byte)((annotations_size>>24)&0xff);
		header[17]=(byte)((annotations_size>>16)&0xff);
		header[18]=(byte)((annotations_size>>8)&0xff);
		header[19]=(byte)(annotations_size&0xff);

		// TODO [20] - [35]  correlation ID 16 bytes

		// header[36] = 0;  // reserved
		// header[37] = 0;	// reserved

		header[38]=(byte)((MAGIC_NUMBER>>8)&0xff);
		header[39]=(byte)(MAGIC_NUMBER&0xff);

		return header;
	}

	public byte[] get_annotations_bytes()
	{
		ArrayList<byte[]> chunks = new ArrayList<byte[]>();
		int total_size = 0;
		for(Entry<String, byte[]> ann: annotations.entrySet())
		{
			String key = ann.getKey();
			byte[] value = ann.getValue();
			if(key.length()!=4)
				throw new IllegalArgumentException("annotation key must be length 4");
			chunks.add(key.getBytes());
			byte[] size_bytes = new byte[] {
					(byte)((value.length>>24)&0xff),
					(byte)((value.length>>16)&0xff),
					(byte)((value.length>>8)&0xff),
					(byte)(value.length&0xff)
			};
			chunks.add(size_bytes);
			chunks.add(value);
			total_size += 4+4+value.length;
		}

		byte[] result = new byte[total_size];
		int index=0;
		for(byte[] chunk: chunks)
		{
			System.arraycopy(chunk, 0, result, index, chunk.length);
			index+=chunk.length;
		}

		return result;
	}


	/**
	 * Parses a message header. Does not yet process the annotations chunks and message data.
	 */
	public static Message from_header(byte[] header)
	{
		if(header==null || header.length!=HEADER_SIZE)
			throw new PyroException("header data size mismatch");

		if(header[0]!='P'||header[1]!='Y'||header[2]!='R'||header[3]!='O')
			throw new PyroException("invalid message");

		int version = ((header[4]&0xff) << 8)|(header[5]&0xff);
		if(version!=Config.PROTOCOL_VERSION)
			throw new PyroException("invalid protocol version: "+version);
		int magic = ((header[38]&0xff) << 8)|(header[39]&0xff);
		if(magic != MAGIC_NUMBER)
			throw new PyroException("invalid header magic number");

		byte msg_type = header[6];
		byte serializer_id = header[7];
		int flags = ((header[8]&0xff) << 8)|(header[9]&0xff);
		int seq = ((header[10]&0xff) << 8)|(header[11]&0xff);

		int data_size=header[12]&0xff;
		data_size<<=8;
		data_size|=header[13]&0xff;
		data_size<<=8;
		data_size|=header[14]&0xff;
		data_size<<=8;
		data_size|=header[15]&0xff;

		int annotations_size=header[16]&0xff;
		annotations_size<<=8;
		annotations_size|=header[17]&0xff;
		annotations_size<<=8;
		annotations_size|=header[18]&0xff;
		annotations_size<<=8;
		annotations_size|=header[19]&0xff;

		// TODO [20] - [35]  correlation ID 16 bytes

		Message msg = new Message(msg_type, serializer_id, flags, seq);
		msg.data_size = data_size;
		msg.annotations_size = annotations_size;
		return msg;
	}


	// Note: this 'chunked' way of sending is not used because it triggers Nagle's algorithm
	// on some systems (linux). This causes massive delays, unless you change the socket option
	// TCP_NODELAY to disable the algorithm. What also works, is sending all the message bytes
	// in one go: connection.send(message.to_bytes())
//    public void send(Stream connection)
//    {
//    	// send the message as bytes over the connection
//    	IOUtil.send(connection, get_header_bytes());
//    	if(annotations_size>0)
//    		IOUtil.send(connection, get_annotations_bytes());
//    	IOUtil.send(connection, data);
//	}


	/**
	 * Receives a pyro message from a given connection.
	 * Accepts the given message types (None=any, or pass a sequence).
	 * Also reads annotation chunks and the actual payload data.
	 */
	public static Message recv(InputStream connection, int[] requiredMsgTypes) throws IOException
	{
		byte[] header_data = IOUtil.recv(connection, HEADER_SIZE);
		Message msg = from_header(header_data);
		if(requiredMsgTypes!=null)
		{
			boolean found=false;
			for(int req: requiredMsgTypes)
			{
				if(req==msg.type)
				{
					found=true;
					break;
				}
			}
			if(!found)
				throw new PyroException(String.format("invalid msg type %d received", msg.type));
		}

		byte[] annotations_data = null;
		msg.annotations = new TreeMap<String, byte[]>();
		if(msg.annotations_size>0)
		{
			// read annotation chunks
			annotations_data = IOUtil.recv(connection, msg.annotations_size);
			int i=0;
			while(i<msg.annotations_size)
			{
				String anno = new String(annotations_data, i, 4);
				int length = (annotations_data[i+4]<<24) | (annotations_data[i+5]<<16) | (annotations_data[i+6]<<8) | annotations_data[i+7];
				byte[] annotations_bytes = new byte[length];
				System.arraycopy(annotations_data, i+8, annotations_bytes, 0, length);
				msg.annotations.put(anno, annotations_bytes);
				i += 8+length;
			}
		}

		// read data
		msg.data = IOUtil.recv(connection, msg.data_size);

		if(Config.MSG_TRACE_DIR!=null) {
			TraceMessageRecv(msg.seq, header_data, annotations_data, msg.data);
		}

		return msg;
	}

	public static void TraceMessageSend(int sequenceNr, byte[] headerdata, byte[] annotations, byte[] data) throws IOException {
		String filename=String.format("%s%s%05d-a-send-header.dat", Config.MSG_TRACE_DIR, File.separator, sequenceNr);
		FileOutputStream fos=new FileOutputStream(filename);
		fos.write(headerdata);
		if(annotations!=null) fos.write(annotations);
		fos.close();
		filename=String.format("%s%s%05d-a-send-message.dat", Config.MSG_TRACE_DIR, File.separator, sequenceNr);
		fos=new FileOutputStream(filename);
		fos.write(data);
		fos.close();
	}

	public static void TraceMessageRecv(int sequenceNr, byte[] headerdata, byte[] annotations, byte[] data) throws IOException {
		String filename=String.format("%s%s%05d-b-recv-header.dat", Config.MSG_TRACE_DIR, File.separator, sequenceNr);
		FileOutputStream fos=new FileOutputStream(filename);
		fos.write(headerdata);
		if(annotations!=null) fos.write(annotations);
		fos.close();
		filename=String.format("%s%s%05d-b-recv-message.dat", Config.MSG_TRACE_DIR, File.separator, sequenceNr);
		fos=new FileOutputStream(filename);
		fos.write(data);
		fos.close();
	}

}
