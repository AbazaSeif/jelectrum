package slopbucket;

import jelectrum.EventLog;

import java.io.File;
import java.io.RandomAccessFile;

import java.util.TreeMap;
import java.nio.channels.FileChannel;
import java.util.Map;
import java.io.IOException;
import java.nio.MappedByteBuffer;

import com.google.protobuf.ByteString;
import org.junit.Assert;

public class Slopbucket
{
  private EventLog log;
  private static final long SEGMENT_FILE_SIZE=Integer.MAX_VALUE;
  
  public static final long MAGIC_LOCATION_ZERO=Long.MAX_VALUE;
  public static final int MAX_TROUGHS=512;
  public static final int MAX_TROUGH_NAME_LEN=128;

  private static final int LOCATION_VERSION=0;
  private static final int LOCATION_NEXT_FREE=LOCATION_VERSION+8;
  private static final int LOCATION_TROUGH_TABLE_START=LOCATION_NEXT_FREE+8;
  private static final int LOCATION_FIRST_FREE=LOCATION_TROUGH_TABLE_START+ (MAX_TROUGH_NAME_LEN + 8) * MAX_TROUGHS;

  private static final int LOCATION_HASH_MAX=0;
  private static final int LOCATION_HASH_ITEMS=LOCATION_HASH_MAX+4;
  private static final int LOCATION_HASH_NEXT=LOCATION_HASH_ITEMS+4;
  private static final int LOCATION_HASH_START=LOCATION_HASH_NEXT+8;
 
  private static final int HASH_INITAL_SIZE=65536;
  private static final int HASH_MULTIPLCATION=8;
  private static final double HASH_FULL=0.5;

  
  private Object ptr_lock = new Object();

  private Map<Integer, MappedByteBuffer> open_buffers;
  private Map<String, Integer> trough_map;
  

  private File slop_dir;

  public Slopbucket(File slop_dir, EventLog log)
  {
    this.log = log;
    this.slop_dir = slop_dir;
    open_buffers = new TreeMap<>();

  }

  protected long getCurrentWriteLocation()
  {
    MappedByteBuffer mbb = getBufferMap(0);
    long v;
    synchronized(mbb)
    {
      v = mbb.getLong((int)LOCATION_NEXT_FREE);
    }
    if (v == 0) return LOCATION_FIRST_FREE;
    return v;

  }
  protected void setCurrentWriteLocation(long v)
  {
    MappedByteBuffer mbb = getBufferMap(0);
    synchronized(mbb)
    {
      mbb.putLong((int)LOCATION_NEXT_FREE, v);
    }
  }

  public Map<String, Integer> getTroughMap()
  {
    if (trough_map != null) return trough_map;

    Map<String, Integer> m = loadTroughMap();
    trough_map = m;
    return m;
    
  }

  protected Map<String, Integer> loadTroughMap()
  {
    TreeMap<String,Integer> map = new TreeMap<>();

    MappedByteBuffer mbb = getBufferMap(0);
    synchronized(mbb)
    {
      for(int i=0; i<MAX_TROUGHS; i++)
      {
        mbb.position( (int)(LOCATION_TROUGH_TABLE_START + (8 + MAX_TROUGH_NAME_LEN) * i));
        long ptr = mbb.getLong();
        byte[] name = new byte[MAX_TROUGH_NAME_LEN];
        mbb.get(name);
        int len =0;
        for(int j=0; (j<MAX_TROUGH_NAME_LEN) && (name[j] != 0); j++)
        {
          len++;
        }
        if (len > 0)
        {
          String name_str = new String(name, 0, len);
          map.put(name_str, i);
        }
        else
        {
          map.put("__FREE", i);
        }

      }
    }
    return map;
  }

  public void addTrough(String name)
  {
    if (name.length() > MAX_TROUGH_NAME_LEN) throw new RuntimeException("OVER MAX NAME LENGTH");

    Map<String, Integer> troughs = getTroughMap();

    if(troughs.containsKey(name)) return;

    if (!troughs.containsKey("__FREE"))
    throw new RuntimeException("TOO MANY TROUGHS");

    int trough_idx = troughs.get("__FREE");

    long hash_loc = makeNewHashTable(HASH_INITAL_SIZE);

    MappedByteBuffer mbb = getBufferMap(0);
    synchronized(mbb)
    {
      mbb.position( (int) (LOCATION_TROUGH_TABLE_START + (8 + MAX_TROUGH_NAME_LEN) * trough_idx));
      mbb.putLong(hash_loc);
      mbb.put(name.getBytes());
    }
    trough_map = null;
  }

  public long getTroughPtr(String name)
  {
    Map<String, Integer> troughs = getTroughMap();
    if (!troughs.containsKey(name))
    {
      throw new RuntimeException("Unable to find trough: " + name);
    }
    int idx=troughs.get(name);
    MappedByteBuffer mbb = getBufferMap(0);
    synchronized(mbb)
    {
      mbb.position( (int) (LOCATION_TROUGH_TABLE_START + (8 + MAX_TROUGH_NAME_LEN) * idx) );
      return mbb.getLong();
    }
  }

  protected long makeNewHashTable(int items)
  {
    items = Math.min(items, (int)(SEGMENT_FILE_SIZE / 8 - 2));
    long hash_loc = allocateSpace((int) (items * 8 + LOCATION_HASH_START));
    writeInt(hash_loc + LOCATION_HASH_MAX, items);
    log.log("Making new hash table of size: " + items + " at " + hash_loc);
    return hash_loc;
  }

  protected void writeLong(long position, long value)
  {
    int file = (int) (position / SEGMENT_FILE_SIZE);
    int offset_in_file = (int) (position % SEGMENT_FILE_SIZE);
    MappedByteBuffer mbb = getBufferMap(file);
    synchronized(mbb)
    {
      mbb.position(offset_in_file);
      mbb.putLong(value);
    }
  }
  protected void writeInt(long position, int value)
  {
    int file = (int) (position / SEGMENT_FILE_SIZE);
    int offset_in_file = (int) (position % SEGMENT_FILE_SIZE);
    MappedByteBuffer mbb = getBufferMap(file);
    synchronized(mbb)
    {
      mbb.position(offset_in_file);
      mbb.putInt(value);
    }
  }



  protected long allocateSpace(int size)
  {
    if (size==0) return MAGIC_LOCATION_ZERO;

    synchronized(ptr_lock)
    {
        long loc = getCurrentWriteLocation();

        long new_end = loc + size + 4;
        //If this would go into the next segment, just go to next segment
        if ((loc / SEGMENT_FILE_SIZE) < (new_end / SEGMENT_FILE_SIZE))
        {
          loc = (new_end / SEGMENT_FILE_SIZE) * SEGMENT_FILE_SIZE;
        }

        long current_write_location = loc + size + 4;
        setCurrentWriteLocation(current_write_location);

        return loc;
    }

  }

  protected MappedByteBuffer getBufferMap(int idx)
  {
    synchronized(open_buffers)
    {
      if (open_buffers.containsKey(idx))
      {
        return open_buffers.get(idx);
      }

      MappedByteBuffer mbb = openFileInternal(idx);
      open_buffers.put(idx, mbb);
      return mbb;


    }
  }

  protected MappedByteBuffer openFileInternal(int idx)
  {
    try
    {
      String name = "" + idx;
      while (name.length() < 4) name = "0" + name;
      File f = new File(slop_dir, "data_" + name);

      RandomAccessFile raf = new RandomAccessFile(f, "rw");
      return raf.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, SEGMENT_FILE_SIZE);

    }
    catch(java.io.IOException e)
    {
      throw new RuntimeException(e);
    }
  }

  public synchronized void putKeyValue(String trough_name, ByteString key, ByteString value)
  {
    long pos = getTroughPtr(trough_name);
    putKeyValueTable(pos, key, value);
  }
  public synchronized ByteString getKeyValue(String trough_name, ByteString key)
  {
    long pos = getTroughPtr(trough_name);
    return getKeyValueTable(pos, key);
  }

  protected ByteString getKeyValueTable(long table_pos, ByteString key)
  {
    int hash_file = (int) (table_pos / SEGMENT_FILE_SIZE);
    MappedByteBuffer hash_mbb = getBufferMap(hash_file);
    int file_offset = (int) (table_pos % SEGMENT_FILE_SIZE);

    int max;
    int items;
    long next_ptr;

    synchronized(hash_mbb)
    {
      hash_mbb.position(file_offset + (int) LOCATION_HASH_MAX); 
      max = hash_mbb.getInt();
      items = hash_mbb.getInt();
      next_ptr = hash_mbb.getLong();
    }

    Assert.assertTrue("Max " + max + " items " + items + " table at " + table_pos + " file " + hash_file + " file offset " + file_offset ,max > items);
    Assert.assertTrue(max > 4);
    Assert.assertTrue(items >= 0);

    int hash = key.hashCode();
    int loc = Math.abs(hash % max);
    if (loc < 0) loc = 0;

    while(true)
    {
      Assert.assertTrue(loc >= 0);
      Assert.assertTrue(loc < max);
      synchronized(hash_mbb)
      {
        hash_mbb.position(file_offset + LOCATION_HASH_START + loc * 8);
        long ptr = hash_mbb.getLong();

        if ((ptr != 0) && (getKey(ptr).equals(key)))
        {
          return getValue(ptr);
        }
        if (ptr == 0)
        {
          if (next_ptr != 0)
          {
            return getKeyValueTable(next_ptr, key); 
          }
          else
          {
            return null;
          }
        }
      }
      
      loc = (loc + 131 ) % max;
    }



  }

  protected void putKeyValueTable(long table_pos, ByteString key, ByteString value)
  {
    int hash_file = (int) (table_pos / SEGMENT_FILE_SIZE);
    MappedByteBuffer hash_mbb = getBufferMap(hash_file);
    int file_offset = (int) (table_pos % SEGMENT_FILE_SIZE);

    int max;
    int items;
    long next_ptr;

    synchronized(hash_mbb)
    {
      hash_mbb.position(file_offset + (int) LOCATION_HASH_MAX); 
      max = hash_mbb.getInt();
      items = hash_mbb.getInt();
      next_ptr = hash_mbb.getLong();
    }

    Assert.assertTrue("Max " + max + " items " + items + " table at " + table_pos + " file " + hash_file + " file offset " + file_offset ,max > items);
    Assert.assertTrue(max > 4);
    Assert.assertTrue(items >= 0);

    int hash = key.hashCode();
    int loc = Math.abs(hash % max);
    if (loc < 0) loc = 0;

    double full = (double) items / (double) max;

    long data_loc = allocateSpace(key.size() + 2 + value.size() + 4);
    putKeyValue(data_loc, key, value);

    while(true)
    {
      Assert.assertTrue(loc >= 0);
      Assert.assertTrue(loc < max);
      synchronized(hash_mbb)
      {
        hash_mbb.position(file_offset + LOCATION_HASH_START + loc * 8);
        long ptr = hash_mbb.getLong();

        if ((ptr == 0) && (full >= HASH_FULL))
        { 
          // It isn't here and the hash is full, move on to next table

          if (next_ptr == 0)
          {
            next_ptr = makeNewHashTable(max * HASH_MULTIPLCATION);
            hash_mbb.position(file_offset + (int) LOCATION_HASH_NEXT);
            hash_mbb.putLong(next_ptr);
          }
          putKeyValueTable(next_ptr, key, value);
          return;
     
        }
        if ((ptr == 0) || (getKey(ptr).equals(key)))
        {
          //If we have an empty or a key match
          hash_mbb.position(file_offset + LOCATION_HASH_START + loc * 8);
          hash_mbb.putLong(data_loc);

          hash_mbb.position(file_offset + LOCATION_HASH_ITEMS);
          items++;
          hash_mbb.putInt(items);
          return;
        }
      }
      
      loc = (loc + 131 ) % max;
    }

  }
  protected void putKeyValue(long data_loc, ByteString key, ByteString value)
  {
    int file = (int) (data_loc / SEGMENT_FILE_SIZE);
    MappedByteBuffer mbb = getBufferMap(file);
    int offset = (int) (data_loc % SEGMENT_FILE_SIZE);
    synchronized(mbb)
    {
      mbb.position(offset);
      mbb.putShort((short)key.size());
      mbb.put(key.toByteArray());
      mbb.putInt(value.size());
      mbb.put(value.toByteArray());
    }
  }
  protected ByteString getKey(long data_loc)
  {
    //System.out.println("data loc: " + data_loc);
    int file = (int) (data_loc / SEGMENT_FILE_SIZE);
    MappedByteBuffer mbb = getBufferMap(file);
    int offset = (int) (data_loc % SEGMENT_FILE_SIZE);
    //System.out.println("File: " + file + " Offset: " + offset);
    synchronized(mbb)
    {
      mbb.position(offset);
      int sz = mbb.getShort();
      byte[] b = new byte[sz];
      mbb.get(b);
      return ByteString.copyFrom(b);

    }
  }
  protected ByteString getValue(long data_loc)
  {
    int file = (int) (data_loc / SEGMENT_FILE_SIZE);
    MappedByteBuffer mbb = getBufferMap(file);
    int offset = (int) (data_loc % SEGMENT_FILE_SIZE);
    synchronized(mbb)
    {
      mbb.position(offset);
      int sz = mbb.getShort();
      byte[] b = new byte[sz];
      mbb.get(b);
      sz = mbb.getInt();
      b = new byte[sz];
      mbb.get(b);
      return ByteString.copyFrom(b);

    }
  }


}
