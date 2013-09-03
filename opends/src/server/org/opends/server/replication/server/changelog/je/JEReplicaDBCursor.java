/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at
 * trunk/opends/resource/legal-notices/OpenDS.LICENSE
 * or https://OpenDS.dev.java.net/OpenDS.LICENSE.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at
 * trunk/opends/resource/legal-notices/OpenDS.LICENSE.  If applicable,
 * add the following below this CDDL HEADER, with the fields enclosed
 * by brackets "[]" replaced with your own identifying information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Copyright 2006-2009 Sun Microsystems, Inc.
 *      Portions Copyright 2011-2013 ForgeRock AS
 */
package org.opends.server.replication.server.changelog.je;

import org.opends.messages.Message;
import org.opends.server.replication.common.CSN;
import org.opends.server.replication.protocol.UpdateMsg;
import org.opends.server.replication.server.changelog.api.ChangelogException;
import org.opends.server.replication.server.changelog.api.ReplicaDBCursor;
import org.opends.server.replication.server.changelog.je.ReplicationDB.*;

/**
 * Berkeley DB JE implementation of {@link ReplicaDBCursor}.
 */
public class JEReplicaDBCursor implements ReplicaDBCursor
{
  private UpdateMsg currentChange = null;
  private ReplServerDBCursor cursor = null;
  private DbHandler dbHandler;
  private ReplicationDB db;
  private CSN lastNonNullCurrentCSN;

  /**
   * Creates a new {@link JEReplicaDBCursor}. All created cursor must be
   * released by the caller using the {@link #close()} method.
   *
   * @param db
   *          The db where the cursor must be created.
   * @param startAfterCSN
   *          The CSN after which the cursor must start.
   * @param dbHandler
   *          The associated DbHandler.
   * @throws ChangelogException
   *           if a database problem happened.
   */
  public JEReplicaDBCursor(ReplicationDB db, CSN startAfterCSN,
      DbHandler dbHandler) throws ChangelogException
  {
    this.db = db;
    this.dbHandler = dbHandler;
    this.lastNonNullCurrentCSN = startAfterCSN;

    try
    {
      cursor = db.openReadCursor(startAfterCSN);
    }
    catch(Exception e)
    {
      // we didn't find it in the db
      cursor = null;
    }

    if (cursor == null)
    {
      // flush the queue into the db
      dbHandler.flush();

      // look again in the db
      cursor = db.openReadCursor(startAfterCSN);
      if (cursor == null)
      {
        throw new ChangelogException(Message.raw("no new change"));
      }
    }
  }

  /** {@inheritDoc} */
  @Override
  public UpdateMsg getChange()
  {
    return currentChange;
  }

  /** {@inheritDoc} */
  @Override
  public boolean next()
  {
    currentChange = cursor.next();

    if (currentChange != null)
    {
      lastNonNullCurrentCSN = currentChange.getCSN();
    }
    else
    {
      synchronized (this)
      {
        if (cursor != null)
        {
          cursor.close();
          cursor = null;
        }
        dbHandler.flush();
        try
        {
          cursor = db.openReadCursor(lastNonNullCurrentCSN);
          currentChange = cursor.next();
          if (currentChange != null)
          {
            lastNonNullCurrentCSN = currentChange.getCSN();
          }
        }
        catch(Exception e)
        {
          currentChange = null;
        }
      }
    }
    return currentChange != null;
  }

  /** {@inheritDoc} */
  @Override
  public void close()
  {
    synchronized (this)
    {
      if (cursor != null)
      {
        cursor.close();
        cursor = null;
      }
      this.dbHandler = null;
      this.db = null;
    }
  }

  /**
   * Called by the Gc when the object is garbage collected Release the internal
   * cursor in case the cursor was badly used and {@link #close()} was never
   * called.
   */
  @Override
  protected void finalize()
  {
    close();
  }
}