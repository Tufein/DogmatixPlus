package com.cortinadev.dogmatix;
import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import java.io.*;
/** Test APK only; Java keeps the remote provider independent of the target APK runtime. */
public class TestStorageProvider extends ContentProvider {
 private File root; private final java.util.Map<String,String> faults=new java.util.HashMap<>();
 public boolean onCreate() { root=new File(getContext().getCacheDir(), "saf-fixture"); root.mkdirs(); return true; }
 private File file(String id) { try { File f=new File(root,id.equals("root")?"":id.substring(5)); if(!f.getCanonicalPath().equals(root.getCanonicalPath()) && !f.getCanonicalPath().startsWith(root.getCanonicalPath()+"/")) throw new IllegalArgumentException(); return f; } catch(IOException e) { throw new IllegalStateException(e); } }
 private String id(File f) { String path=f.getAbsolutePath().substring(root.getAbsolutePath().length()); return "root"+path; }
 private File document(Uri uri) { return file(DocumentsContract.getDocumentId(uri)); }
 public String getType(Uri uri) { return document(uri).isDirectory()?DocumentsContract.Document.MIME_TYPE_DIR:"application/octet-stream"; }
 public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) {
  String[] cols=projection!=null?projection:new String[]{"document_id","_display_name","mime_type","flags","_size","last_modified"};
  MatrixCursor c=new MatrixCursor(cols); File p=document(uri); File[] files="children".equals(uri.getLastPathSegment())?p.listFiles():p.exists()?new File[]{p}:new File[0];
  if(files!=null) for(File f:files) { Object[] row=new Object[cols.length]; for(int i=0;i<cols.length;i++) { switch(cols[i]) {
   case "document_id":row[i]=id(f);break; case "_display_name":row[i]=f.getName();break;
   case "mime_type":row[i]=f.isDirectory()?DocumentsContract.Document.MIME_TYPE_DIR:"application/octet-stream";break;
   case "_size":row[i]=f.length();break;case "last_modified":row[i]=f.lastModified();break;
   case "flags":row[i]=DocumentsContract.Document.FLAG_SUPPORTS_DELETE|DocumentsContract.Document.FLAG_SUPPORTS_RENAME|DocumentsContract.Document.FLAG_SUPPORTS_WRITE|(f.isDirectory()?DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE:0);break;
  }} c.addRow(row); } return c;
 }
 public Bundle call(String method,String arg,Bundle data) {
  if(method.startsWith("fixture:fail_")) { synchronized(faults) { if(arg==null) faults.remove(method); else faults.put(method,arg); } return new Bundle(); }
  if("fixture:clear_faults".equals(method)) { synchronized(faults) { faults.clear(); } return new Bundle(); }
  try { Bundle result=new Bundle();
   if("android:createDocument".equals(method)) { Uri parent=data.getParcelable("uri"); String name=data.getString("_display_name"); valid(name); if(fails("fixture:fail_create",name)||fails("fixture:fail_commit",name)) throw new IOException("Injected create failure"); if(fails("fixture:fail_display_name",name)) name+=".changed"; File target=new File(document(parent),name); if(target.exists() || !(DocumentsContract.Document.MIME_TYPE_DIR.equals(data.getString("mime_type"))?target.mkdir():target.createNewFile())) throw new IOException(); result.putParcelable("uri",DocumentsContract.buildDocumentUriUsingTree(parent,id(target))); return result; }
   if("android:renameDocument".equals(method)) { Uri uri=data.getParcelable("uri"); File original=document(uri); String name=data.getString("_display_name"); valid(name); if(fails("fixture:fail_rename",name)||fails("fixture:fail_commit",name)) throw new IOException("Injected rename failure"); File target=new File(original.getParentFile(),name); if(target.exists()||!original.renameTo(target)) throw new IOException(); if(fails("fixture:fail_read_after_rename",name)) { synchronized(faults) { faults.put("fixture:fail_read",name); } } if(fails("fixture:fail_modified_after_rename",name)) { try(FileOutputStream output=new FileOutputStream(target)) { output.write("external newer bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8)); } } result.putParcelable("uri",DocumentsContract.buildDocumentUriUsingTree(uri,id(target))); return result; }
   if("android:deleteDocument".equals(method)) { File target=document(data.getParcelable("uri")); if(fails("fixture:fail_delete",target.getName())) throw new IOException("Injected delete failure"); erase(target); return result; }
   return super.call(method,arg,data);
  } catch(IOException e) { throw new IllegalStateException(e); }
 }
 private boolean fails(String method,String name) { synchronized(faults) { String pattern=faults.get(method); return pattern!=null&&(pattern.endsWith("*")?name.startsWith(pattern.substring(0,pattern.length()-1)):pattern.startsWith("*")?name.endsWith(pattern.substring(1)):name.equals(pattern)); } }
 private void valid(String name) { if(name==null||name.contains("/")||name.contains("\\")||name.equals("..")) throw new IllegalArgumentException(); }
 private void erase(File f) throws IOException { File[] children=f.listFiles(); if(children!=null) for(File child:children) erase(child); if(!f.delete()) throw new IOException(); }
 public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException { File f=document(uri); if(mode.equals("r")&&fails("fixture:fail_read",f.getName())) throw new FileNotFoundException("Injected read failure"); if(!mode.equals("r")&&fails("fixture:fail_write",f.getName())) throw new FileNotFoundException("Injected write failure"); return ParcelFileDescriptor.open(f,ParcelFileDescriptor.parseMode(mode)); }
 public Uri insert(Uri uri,ContentValues values) { return null; }
 public int update(Uri uri,ContentValues values,String selection,String[] args) { return 0; }
 public int delete(Uri uri,String selection,String[] args) { return 0; }
}
