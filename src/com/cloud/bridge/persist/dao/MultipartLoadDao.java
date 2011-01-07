package com.cloud.bridge.persist.dao;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Calendar;
import java.util.Date;
import java.util.Properties;

import org.apache.log4j.Logger;

import com.cloud.bridge.model.UserCredentials;
import com.cloud.bridge.service.UserContext;
import com.cloud.bridge.service.core.s3.S3MetaDataEntry;
import com.cloud.bridge.service.core.s3.S3MultipartPart;
import com.cloud.bridge.util.ConfigurationHelper;

public class MultipartLoadDao {
	public static final Logger logger = Logger.getLogger(MultipartLoadDao.class);

	private Connection conn       = null;
	private String     dbName     = null;
	private String     dbUser     = null;
	private String     dbPassword = null;
	
	public MultipartLoadDao() {
	    File propertiesFile = ConfigurationHelper.findConfigurationFile("ec2-service.properties");
	    Properties EC2Prop = null;
	       
	    if (null != propertiesFile) {
	   	    EC2Prop = new Properties();
	    	try {
				EC2Prop.load( new FileInputStream( propertiesFile ));
			} catch (FileNotFoundException e) {
				logger.warn("Unable to open properties file: " + propertiesFile.getAbsolutePath(), e);
			} catch (IOException e) {
				logger.warn("Unable to read properties file: " + propertiesFile.getAbsolutePath(), e);
			}
		    dbName     = EC2Prop.getProperty( "dbName" );
		    dbUser     = EC2Prop.getProperty( "dbUser" );
		    dbPassword = EC2Prop.getProperty( "dbPassword" );
		}
	}
	
	/**
	 * If a multipart upload exists with the uploadId value then return the non-null creators
	 * accessKey.
	 * 
	 * @param uploadId
	 * @return creator of the multipart upload
	 * @throws SQLException, ClassNotFoundException, IllegalAccessException, InstantiationException 
	 */
	public String multipartExits( int uploadId ) 
	    throws InstantiationException, IllegalAccessException, ClassNotFoundException, SQLException
	{
	    PreparedStatement statement = null;
	    String accessKey = null;
		
        openConnection();	
        try {            
		    statement = conn.prepareStatement ( "SELECT AccessKey FROM multipart_uploads WHERE ID=?" );
	        statement.setInt( 1, uploadId );
	        ResultSet rs = statement.executeQuery();
		    if (rs.next()) accessKey = rs.getString( "AccessKey" );
            return accessKey;
        
        } finally {
            closeConnection();
        }
	}
	
	/**
	 * The multipart upload was either successfully completed or was aborted.   In either case, we need
	 * to remove all of its state from the tables.   Note that we have cascade deletes so all tables with
	 * uploadId as a foreign key are automatically cleaned.
	 * 
	 * @param uploadId
	 * 
	 * @throws SQLException, ClassNotFoundException, IllegalAccessException, InstantiationException 
	 */
	public void deleteUpload( int uploadId )
        throws InstantiationException, IllegalAccessException, ClassNotFoundException, SQLException
	{
	    PreparedStatement statement = null;
		
        openConnection();	
        try {
		    statement = conn.prepareStatement ( "DELETE FROM multipart_uploads WHERE ID=?" );
	        statement.setInt( 1, uploadId );
	        int count = statement.executeUpdate();
            statement.close();	
        
        } finally {
            closeConnection();
        }
	}
	
	/**
	 * The caller needs to know who initiated the multipart upload.
	 * 
	 * @param uploadId
	 * @return the access key value defining the initiator
	 * @throws InstantiationException, IllegalAccessException, ClassNotFoundException, SQLException
	 */
	public String getUploadInitiator( int uploadId ) 
        throws InstantiationException, IllegalAccessException, ClassNotFoundException, SQLException
	{
	    PreparedStatement statement = null;
	    String initiator = null;
		
        openConnection();	
        try {
		    statement = conn.prepareStatement ( "SELECT AccessKey FROM multipart_uploads WHERE ID=?" );
	        statement.setInt( 1, uploadId );
	        ResultSet rs = statement.executeQuery();
		    if (rs.next()) initiator = rs.getString( "AccessKey" );
            statement.close();			    
            return initiator;
        
        } finally {
            closeConnection();
        }
	}
	
	/**
	 * Create a new "in-process" multipart upload entry to keep track of its state.
	 * 
	 * @param accessKey
	 * @param bucketName
	 * @param key
	 * @param cannedAccess
	 * 
	 * @return if positive its the uploadId to be returned to the client
	 * 
	 * @throws SQLException, ClassNotFoundException, IllegalAccessException, InstantiationException 
	 */
	public int initiateUpload( String accessKey, String bucketName, String key, String cannedAccess, S3MetaDataEntry[] meta ) 
	    throws InstantiationException, IllegalAccessException, ClassNotFoundException, SQLException
	{
	    PreparedStatement statement = null;
		int uploadId = -1;
		
        openConnection();	
        try {
	        Date tod = new Date();
	        java.sql.Date sqlDate = new java.sql.Date( tod.getTime());

		    statement = conn.prepareStatement ( "INSERT INTO multipart_uploads (AccessKey, BucketName, NameKey, x_amz_acl, CreateTime) VALUES (?,?,?,?,?)" );
	        statement.setString( 1, accessKey );
	        statement.setString( 2, bucketName );
	        statement.setString( 3, key );
	        statement.setString( 4, cannedAccess );      
	        statement.setDate( 5, sqlDate );
            int count = statement.executeUpdate();
            statement.close();	
            
            // -> we need the newly entered ID 
		    statement = conn.prepareStatement ( "SELECT ID FROM multipart_uploads WHERE AccessKey=? AND BucketName=? AND NameKey=? AND CreateTime=?" );
	        statement.setString( 1, accessKey );
	        statement.setString( 2, bucketName );
	        statement.setString( 3, key );
	        statement.setDate( 4, sqlDate );
	        ResultSet rs = statement.executeQuery();
		    if (rs.next()) {
		    	uploadId = rs.getInt( "ID" );
		        saveMultipartMeta( uploadId, meta );
		    }
            statement.close();			    
            return uploadId;
        
        } finally {
            closeConnection();
        }
	}
	
	/**
	 * Remember all the individual parts that make up the entire multipart upload so that once
	 * the upload is complete all the parts can be glued together into a single object.  Note, 
	 * the caller can over write an existing part.
	 * 
	 * @param uploadId
	 * @param partNumber
	 * @param md5
	 * @param storedPath
	 * @param size
	 * @throws InstantiationException, IllegalAccessException, ClassNotFoundException, SQLException
	 */
	public void saveUploadPart( int uploadId, int partNumber, String md5, String storedPath, int size ) 
        throws InstantiationException, IllegalAccessException, ClassNotFoundException, SQLException
    {
        PreparedStatement statement = null;
        int id = -1;
        int count = 0;
	
        openConnection();	
        try {
            Date tod = new Date();
            java.sql.Date sqlDate = new java.sql.Date( tod.getTime());

            // -> are we doing an update or an insert?  (are we over writting an existing entry?)
		    statement = conn.prepareStatement ( "SELECT ID FROM multipart_parts WHERE UploadID=? AND partNumber=?" );
            statement.setInt( 1, uploadId );
            statement.setInt( 2, partNumber  );
            ResultSet rs = statement.executeQuery();
		    if (rs.next()) id = rs.getInt( "ID" );
            statement.close();			    

            if ( -1 == id )
            {
	             statement = conn.prepareStatement ( "INSERT INTO multipart_parts (UploadID, partNumber, MD5, StoredPath, StoredSize, CreateTime) VALUES (?,?,?,?,?,?)" );
                 statement.setInt(    1, uploadId );
                 statement.setInt(    2, partNumber );
                 statement.setString( 3, md5 );
                 statement.setString( 4, storedPath );   
                 statement.setInt(    5, size );
                 statement.setDate(   6, sqlDate );
            }
            else
            {    statement = conn.prepareStatement ( "UPDATE multipart_parts SET MD5=?, StoredSize=?, CreateTime=? WHERE UploadId=? AND partNumber=?" );
                 statement.setString( 1, md5 );
                 statement.setInt(    2, size );
                 statement.setDate(   3, sqlDate );
                 statement.setInt(    4, uploadId );
                 statement.setInt(    5, partNumber );
            }
            count = statement.executeUpdate();
            statement.close();	
            
        } finally {
            closeConnection();
        }
    }
	
	/**
	 * Return info on a range of upload parts that have already been stored in disk.
	 * Note that parts can be uploaded in any order yet we must returned an ordered list
	 * of parts thus we use the "ORDERED BY" clause to sort the list.
	 * 
	 * @param uploadId
	 * @param maxParts
	 * @param startAt
	 * @return an array of S3MultipartPart objects
	 * @throws InstantiationException, IllegalAccessException, ClassNotFoundException, SQLException
	 */
	public S3MultipartPart[] getUploadParts( int uploadId, int maxParts, int startAt ) 
	    throws InstantiationException, IllegalAccessException, ClassNotFoundException, SQLException
	{
		S3MultipartPart[] parts = new S3MultipartPart[maxParts];
	    PreparedStatement statement = null;
	    int i = 0;
		
        openConnection();	
        try {
		    statement = conn.prepareStatement ( "SELECT   partNumber, MD5, StoredSize, StoredPath, CreateTime " +
		    		                            "FROM     multipart_parts " +
		    		                            "WHERE    UploadID=? " +
		    		                            "AND      partNumber > ? AND partNumber < ? " +
		    		                            "ORDER BY partNumber" );
	        statement.setInt( 1, uploadId );
	        statement.setInt( 2, startAt  );
	        statement.setInt( 3, startAt + maxParts + 1 );
		    ResultSet rs = statement.executeQuery();
		    
		    while (rs.next() && i < maxParts) 
		    {
		    	Calendar tod = Calendar.getInstance();
		    	tod.setTime( rs.getDate( "CreateTime" ));
		    	
		    	parts[i] = new S3MultipartPart();
		    	parts[i].setPartNumber( rs.getInt( "partNumber" )); 
		    	parts[i].setEtag( rs.getString( "MD5" ));
		    	parts[i].setLastModified( tod );
		    	parts[i].setSize( rs.getInt( "StoredSize" ));
		    	parts[i].setPath( rs.getString( "StoredPath" ));
		    	i++;
		    }
            statement.close();		
            
            if (i < maxParts) parts = (S3MultipartPart[])resizeArray(parts,i);
            return parts;
        
        } finally {
            closeConnection();
        }
	}
  
	/**
	 * How many parts exist after the endMarker part number?
	 * 
	 * @param uploadId
	 * @param endMarker - can be used to see if getUploadedParts was truncated
	 * @return number of parts with partNumber greater than endMarker
	 * @throws InstantiationException, IllegalAccessException, ClassNotFoundException, SQLException
	 */
	public int numUploadParts( int uploadId, int endMarker ) 
        throws InstantiationException, IllegalAccessException, ClassNotFoundException, SQLException
    {
        PreparedStatement statement = null;
        int count = 0;
	
        openConnection();	
        try {
	        statement = conn.prepareStatement ( "SELECT count(*) FROM multipart_parts WHERE UploadID=? AND partNumber > ?" );
            statement.setInt( 1, uploadId );
            statement.setInt( 2, endMarker );
	        ResultSet rs = statement.executeQuery();	    
	        if (rs.next()) count = rs.getInt( 1 );
            statement.close();			    
            return count;
    
        } finally {
            closeConnection();
        }
    }

	/**
	 * A multipart upload request can have zero to many meta data entries to be applied to the
	 * final object.   We need to remember all of the objects meta data until the multipart is complete.
	 * 
	 * @param uploadId - defines an in-process multipart upload
	 * @param meta - an array of meta data to be assocated with the uploadId value
	 * 
	 * @throws SQLException, ClassNotFoundException, IllegalAccessException, InstantiationException 
	 */
	private void saveMultipartMeta( int uploadId, S3MetaDataEntry[] meta ) 
	    throws InstantiationException, IllegalAccessException, ClassNotFoundException, SQLException
	{
		if (null == meta) return;	
	    PreparedStatement statement = null;
		
        openConnection();	
        try {
            for( int i=0; i < meta.length; i++ ) 
            {
               S3MetaDataEntry entry = meta[i];
		       statement = conn.prepareStatement ( "INSERT INTO multipart_meta (UploadID, Name, Value) VALUES (?,?,?)" );
	           statement.setInt( 1, uploadId );
	           statement.setString( 2, entry.getName());
	           statement.setString( 3, entry.getValue());
               int count = statement.executeUpdate();
               statement.close();
            }
            
        } finally {
            closeConnection();
        }
	}
	
	private void openConnection() 
        throws InstantiationException, IllegalAccessException, ClassNotFoundException, SQLException {
        if (null == conn) {
	        Class.forName( "com.mysql.jdbc.Driver" ).newInstance();
            conn = DriverManager.getConnection( "jdbc:mysql://localhost:3306/"+dbName, dbUser, dbPassword );
        }
	}

    private void closeConnection() throws SQLException {
	    if (null != conn) conn.close();
	    conn = null;
    }
    
    /**
    * Reallocates an array with a new size, and copies the contents
    * of the old array to the new array.
    * 
    * @param oldArray  the old array, to be reallocated.
    * @param newSize   the new array size.
    * @return          A new array with the same contents.
    */
    private static Object resizeArray(Object oldArray, int newSize) 
    {
       int oldSize = java.lang.reflect.Array.getLength(oldArray);
       Class elementType = oldArray.getClass().getComponentType();
       Object newArray = java.lang.reflect.Array.newInstance(
             elementType,newSize);
       int preserveLength = Math.min(oldSize,newSize);
       if (preserveLength > 0)
          System.arraycopy (oldArray,0,newArray,0,preserveLength);
       return newArray; 
    }
}
