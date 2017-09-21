package jelectrum;

import jelectrum.db.DBFace;

import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.Address;
import org.bitcoinj.script.ScriptException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.script.Script;
import java.util.HashSet;
import java.util.Map;
import java.util.Collection;
import java.util.Set;
import com.google.protobuf.ByteString;

import org.junit.Assert;

public class TXUtil
{
 
  private DBFace db;
  private NetworkParameters params;
  private LRUCache<Sha256Hash, Transaction> transaction_cache;

  public TXUtil(DBFace db, NetworkParameters params)
  {
    this.db = db;
    this.params = params;
  }

  public synchronized void saveTxCache(Transaction tx)
  {
    if (transaction_cache == null)
    {
      transaction_cache = new LRUCache<Sha256Hash, Transaction>(64000);
    }
    transaction_cache.put(tx.getHash(), SerializedTransaction.scrubTransaction(params,tx));

  }
  public synchronized void putTxCacheIfOpen(Transaction tx)
  {
    if (transaction_cache != null)
    {
      transaction_cache.put(tx.getHash(), SerializedTransaction.scrubTransaction(params,tx));
    }
  }

  public synchronized Transaction getTransaction(Sha256Hash hash)
  { 
    Transaction tx = null;
    if (transaction_cache != null) 
    {
      tx = transaction_cache.get(hash);
    }
    if (tx != null) return tx;

    SerializedTransaction s_tx = db.getTransaction(hash);

    if (s_tx != null)
    { 
      tx = s_tx.getTx(params);
      putTxCacheIfOpen(tx);
      return tx;
    }
    return null;
  }

    public Address getAddressForOutput(TransactionOutput out)
    {
      try
      {
        Script script = out.getScriptPubKey();
        if (script.isSentToRawPubKey())
        {
          byte[] key = out.getScriptPubKey().getPubKey();
          byte[] address_bytes = org.bitcoinj.core.Utils.sha256hash160(key);
          Address a = new Address(params, address_bytes);
          return a;
        }
        else
        {
          Address a = script.getToAddress(params);
          return a;
        }
      }
      catch(ScriptException e)
      {

        //System.out.println(out.getParentTransaction().getHash() + " - " + out);
        //e.printStackTrace();
        //jelly.getEventLog().log("Unable process tx output: " + out.getParentTransaction().getHash());
      }
      return null;

    }

    public HashSet<ByteString> getAllPublicKeys(Transaction tx, boolean confirmed, Map<Sha256Hash, Transaction> block_tx_map)
    {
        HashSet<ByteString> lst = new HashSet<ByteString>();
        boolean detail = false;

        for(TransactionInput in : tx.getInputs())
        {   
            Address a = getAddressForInput(in, confirmed, block_tx_map);
            if (a!=null) lst.add(ByteString.copyFrom(a.getHash160()));
        }

        for(TransactionOutput out : tx.getOutputs())
        {   
            Address a = getAddressForOutput(out);
            if (a!=null) lst.add(ByteString.copyFrom(a.getHash160()));

        }
        return lst;

    }

    public HashSet<String> getAllAddresses(Transaction tx, boolean confirmed, Map<Sha256Hash, Transaction> block_tx_map)
    {   
        HashSet<String> lst = new HashSet<String>();
        boolean detail = false;

        for(TransactionInput in : tx.getInputs())
        {   
            Address a = getAddressForInput(in, confirmed, block_tx_map);
            if (a!=null) lst.add(a.toString());
        }

        for(TransactionOutput out : tx.getOutputs())
        {   
            Address a = getAddressForOutput(out);
            if (a!=null) lst.add(a.toString());

        }
        return lst;
    }

    public Address getAddressForInput(TransactionInput in, boolean confirmed, Map<Sha256Hash, Transaction> block_tx_map)
    {
        if (in.isCoinBase()) return null;

        try
        {
            Address a = in.getFromAddress();
            return a;
        }
        catch(ScriptException e)
        {
          //Lets try this the other way
          try
          {

            TransactionOutPoint out_p = in.getOutpoint();

            Transaction src_tx = null;
            int fail_count =0;
            while(src_tx == null)
            {
              if (block_tx_map != null)
              { 
                src_tx = block_tx_map.get(out_p.getHash());
              }
              if (src_tx == null)
              { 
                src_tx = getTransaction(out_p.getHash());
                if (src_tx == null)
                {   
                  if (!confirmed)
                  {   
                      return null;
                  }
                  fail_count++;
                  if (fail_count > 30)
                  {
                    System.out.println("Unable to get source transaction: " + out_p.getHash());
                  }
                  if (fail_count > 240)
                  {
                    throw new RuntimeException("Waited too long to get transaction: " + out_p.getHash());
                  }
                  try{Thread.sleep(500);}catch(Exception e7){}
                }
              }
            }
            TransactionOutput out = src_tx.getOutput((int)out_p.getIndex());
            Address a = getAddressForOutput(out);
            return a;
          }
          catch(ScriptException e2)
          {   
              return null;
          }
        }

    }
    

  public int getTXBlockHeight(Transaction tx, BlockChainCache chain_cache, BitcoinRPC rpc)
  {
    Sha256Hash block_hash = rpc.getTransactionConfirmationBlock(tx.getHash());

    if (block_hash == null) return -1;
    return db.getBlockStoreMap().get(block_hash).getHeight();

  }

  public String getAddressFromPublicKeyHash(ByteString hash)
  {
    Address a = new Address(params, hash.toByteArray());
    return a.toString();
  }

}
