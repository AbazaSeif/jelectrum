package jelectrum;

import org.apache.commons.codec.binary.Hex;
import java.io.ByteArrayOutputStream;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Sha256Hash;


public class HeaderChunkAgent extends Thread
{
    private Jelectrum jelly;
    private volatile int height;
    private int last_saved_height;

    public HeaderChunkAgent(Jelectrum jelly)
    {
        this.jelly = jelly;
        setName("HeaderChunkAgent");
        setDaemon(true);

    }

    public String getChunk(int index)
    {
        return jelly.getDB().getHeaderChunkMap().get(index);
    }
    public void poke(int new_height)
    {
        height = new_height;
        synchronized(this)
        {
            this.notifyAll();
        }
       
    }

    public void run()
    {
        boolean first=true;

        while(true)
        {
            try
            {
                if (first)
                {
                    height = jelly.getBlockStore().getChainHead().getHeight();
                    first=false;
                }

                doPass();
            }
            catch(Throwable t)
            {
                t.printStackTrace();
            }
            try
            {
                synchronized(this)
                {
                    this.wait();
                }
            }
            catch(java.lang.InterruptedException e)
            {
                e.printStackTrace();
            }
        }



    }
    private void doPass()
        throws org.bitcoinj.store.BlockStoreException
    {
        int h = height;
        for(int i=0; i<h; i+=2016)
        {
            int index= i /2016;
            //System.out.println("index: " + index+ " i: " + i + " h: " + h);
            //if (i+2016 <= h)
            {
                checkChunk(i/2016);
                last_saved_height = i+2016;
            }
        }


    }

    private void checkChunk(int index)
        throws org.bitcoinj.store.BlockStoreException
    {
        {
          String old = jelly.getDB().getHeaderChunkMap().get(index);
          if ((old != null) && (old.length() == 2 * 2016 * 80)) return;
        }

        jelly.getEventLog().log("Building header chunk " + index);

        ByteArrayOutputStream b_out = new ByteArrayOutputStream();
        int start = index * 2016;
        for(int i=0; i<2016; i++)
        {
            if (start+i <= height)
            {
                Sha256Hash hash = jelly.getBlockChainCache().getBlockHashAtHeight(start+i);
                if (hash == null) {
                  int h = start+i;
                  System.out.println("unable to find hash for height: " + h);
                }
                StoredBlock blk = jelly.getBlockStore().get(hash);
                byte[] b = blk.getHeader().bitcoinSerialize();

                b_out.write(b,0,80);
            }

        }
        byte[] buff = b_out.toByteArray();

        String chunk = new String(Hex.encodeHex(buff));
        
        jelly.getDB().getHeaderChunkMap().put(index, chunk);

    }



}
