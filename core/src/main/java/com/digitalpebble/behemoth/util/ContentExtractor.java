/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.digitalpebble.behemoth.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveOutputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.lang.ArrayUtils;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.SequenceFile.Reader;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.SequenceFileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.digitalpebble.behemoth.Annotation;
import com.digitalpebble.behemoth.BehemothConfiguration;
import com.digitalpebble.behemoth.BehemothDocument;
import com.digitalpebble.behemoth.DocumentFilter;

/**
 * Stores the content from Behemoth documents into a local directory
 **/
public class ContentExtractor extends Configured implements Tool {

    private static final Logger LOG = LoggerFactory
            .getLogger(ContentExtractor.class);

    public static final String numEntriesPerArchiveParamName = "numEntriesPerArchive";

    public enum FileNamingMode {
        URL, UUID, NUM;

        public static FileNamingMode toMode(String str) {
            try {
                return valueOf(str);
            } catch (Exception ex) {
                return UUID;
            }
        }
    }

    private FileNamingMode mode = FileNamingMode.UUID;

    // dump the text otherwise
    private boolean dumpBinary = false;
    // don't dump annotations by default
    private boolean dumpAnnotations = false;

    private ArchiveOutputStream currentArchive = null;

    private FSDataOutputStream index = null;

    private int partNum = -1;
    private int numEntriesInCurrentArchive = 0;
    private int maxNumEntriesInArchive = 10000;

    public ContentExtractor() {
    }

    public static void main(String[] args) throws Exception {
        int res = ToolRunner.run(BehemothConfiguration.create(),
                new ContentExtractor(), args);
        System.exit(res);
    }

    public int run(String[] args) throws Exception {

        Options options = new Options();
        // automatically generate the help statement
        HelpFormatter formatter = new HelpFormatter();
        // create the parser
        CommandLineParser parser = new GnuParser();

        options.addOption("h", "help", false, "print this message");
        options.addOption("i", "input", true, "Behemoth corpus");
        options.addOption("o", "output", true, "local corpus dir");
        options.addOption("b", "binary", false,
                "dumps binary content, text otherwise");
        options.addOption("n", "filenaming", true,
                "whether to name files based on URL, UUID (default) or NUM");
        options.addOption("a", "annotation", false, "whether to include annotation in output (off by default)");

        // parse the command line arguments
        try {
            CommandLine line = parser.parse(options, args);
            String input = line.getOptionValue("i");
            String output = line.getOptionValue("o");
            if (line.hasOption("help")) {
                formatter.printHelp("ContentExtractor", options);
                return 0;
            }
            if (input == null || output == null) {
                formatter.printHelp("ContentExtractor", options);
                return -1;
            }
            dumpBinary = line.hasOption("binary");
            dumpAnnotations = line.hasOption("annotation");

            if (line.hasOption("filenaming")) {
                String naming = line.getOptionValue("n");
                mode = FileNamingMode.toMode(naming);
            }

            return generateDocs(input, output);

        } catch (ParseException e) {
            formatter.printHelp("ContentExtractor", options);
            return -1;
        }
    }

    private int generateDocs(String inputf, String outputf) throws IOException,
            ArchiveException {

        Path input = new Path(inputf);
        Path dirPath = new Path(outputf);

        FileSystem fsout = FileSystem.get(dirPath.toUri(), getConf());

        if (fsout.exists(dirPath) == false)
            fsout.mkdirs(dirPath);
        else {
            System.err.println("Output " + outputf + " already exists");
            return -1;
        }

        // index file
        Path indexPath = new Path(dirPath, "index");
        if (fsout.exists(indexPath) == false) {
            fsout.createNewFile(indexPath);
        }

        maxNumEntriesInArchive = getConf().getInt(
                numEntriesPerArchiveParamName, 10000);

        index = fsout.create(indexPath);

        createArchive(dirPath);

        FileSystem fs = input.getFileSystem(getConf());
        FileStatus[] statuses = fs.listStatus(input);
        int count[] = { 0 };
        for (int i = 0; i < statuses.length; i++) {
            FileStatus status = statuses[i];
            Path suPath = status.getPath();
            if (suPath.getName().equals("_SUCCESS"))
                continue;
            generateDocs(suPath, dirPath, count);
        }

        if (index != null)
            index.close();

        if (currentArchive != null) {
            currentArchive.finish();
            currentArchive.close();
        }

        return 0;
    }

    private void createArchive(Path dirPath) throws IOException,
            ArchiveException {
        FileSystem fsout = FileSystem.get(dirPath.toUri(), getConf());
        String archiveType = "zip";
        partNum++;
        FSDataOutputStream currentArchiveOS = fsout.create(new Path(dirPath,
                "part_" + String.format("%06d", partNum) + "." + archiveType));
        currentArchive = new ArchiveStreamFactory().createArchiveOutputStream(
                archiveType, currentArchiveOS);
        numEntriesInCurrentArchive = 0;
    }

    private void addToArchive(String fileName, byte[] content, Path dirPath)
            throws IOException, ArchiveException {
        numEntriesInCurrentArchive++;
        currentArchive.putArchiveEntry(new ZipArchiveEntry(fileName));
        currentArchive.write(content);
        LOG.debug("Successfully wrote BehemothDocument 'content' to output.");
        currentArchive.closeArchiveEntry();
        index.flush();
        if (numEntriesInCurrentArchive == maxNumEntriesInArchive) {
            currentArchive.finish();
            currentArchive.close();
            createArchive(dirPath);
        }
    }

    @SuppressWarnings("unchecked")
	private void generateDocs(Path input, Path dir, int[] count)
            throws IOException, ArchiveException {

        DocumentFilter docFilter = DocumentFilter.getFilters(getConf());

        Reader[] cacheReaders = SequenceFileOutputFormat.getReaders(getConf(),
                input);
        for (Reader current : cacheReaders) {
            // read the key + values in that file
            Text key = new Text();
            BehemothDocument inputDoc = new BehemothDocument();
            while (current.next(key, inputDoc)) {
                count[0]++;
                // filter the doc?
                if (!docFilter.keep(inputDoc))
                    continue;
                if (dumpBinary && inputDoc.getContent() == null)
                    continue;
                else if (!dumpBinary && inputDoc.getText() == null)
                    continue;

                String fileName = Integer.toString(count[0]);
                String urldoc = inputDoc.getUrl();
                if (mode.equals(FileNamingMode.URL) && urldoc != null
                        && urldoc.length() > 0) {
                    fileName = URLEncoder.encode(urldoc, "UTF-8");
                } else if (mode.equals(FileNamingMode.UUID) && urldoc != null
                        && urldoc.length() > 0) {
                    fileName = UUID.nameUUIDFromBytes(urldoc.getBytes())
                            .toString();
                } else {
                    fileName = String.format("%09d", count[0]);
                }

                if (!dumpBinary)
                    fileName += ".txt";

                byte[] contentBytes;
                List<Annotation> annots = null;
                if (dumpBinary) {
                    contentBytes = inputDoc.getContent();
                    if(dumpAnnotations) {
                        annots = inputDoc.getAnnotations();
                        //write annotations with content?
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        ObjectOutputStream oos = new ObjectOutputStream(bos);
                        oos.writeObject(annots);
                        byte[] annotsBytes = bos.toByteArray();
                        contentBytes = concatAndDeepClone(contentBytes, annotsBytes);
                    }
                } else {
                    contentBytes = inputDoc.getText().getBytes("UTF-8");
                    JSONObject contentJSON = null;
                    String content=null,cleanContent=null;
                    try {
                    	content = inputDoc.getText();
                    	cleanContent = content.substring(content.indexOf("{\"_score"));

						contentJSON = (JSONObject) new JSONParser().parse(cleanContent);
						
					} catch (org.json.simple.parser.ParseException e) {
						e.printStackTrace();
					}
                    if(dumpAnnotations) {
                        annots = inputDoc.getAnnotations();
                        ArrayList<String> annotsArrayList = new ArrayList<String>();
                        
                        HashMap<String, Collection<String>> keywordsMap = new HashMap<String, Collection<String>>();
                        
                        for (int i = 0; i < annots.size(); i++) {
                        	
                        	Collection<String> keywords;   
                        	if(keywordsMap.get("\"" + annots.get(i).getType() + "\"")  != null){
                        		 keywords= keywordsMap.get("\"" + annots.get(i).getType() + "\"");
                        	   }
                        	else{
                        		keywords = new HashSet<String>();
                        	}
                        	   Collection<String> featureValues = annots.get(i).getFeatures().values();
                        	   featureValues.removeAll(Arrays.asList(null,""));
                        	   for(String featureValue:featureValues){
                        		   keywords.add("\""+featureValue+"\"");   
                        	   }
                        	   
                        	   keywordsMap.put("\"" + annots.get(i).getType() + "\"", keywords);
                        	   
                          System.out.println(keywordsMap);
                          annotsArrayList.add(annots.get(i).toString());
                        }
                        contentJSON.put("wordslists", keywordsMap);
                        contentBytes = contentJSON.toJSONString().getBytes(Charset.forName("UTF-8"));
                    }
                }
                
                addToArchive(fileName, contentBytes, dir);

                // add the mapping URL->filename in the index -> archive num
                index.writeBytes(urldoc + "\t" + fileName + "\t"
                        + String.format("%06d", partNum) + "\n");
            }
            current.close();
        }
    }
    
    private byte[] concatAndDeepClone(byte[] contentBytes, byte[] annotsBytes) {
      byte[] concatBytes = ArrayUtils.addAll(contentBytes, annotsBytes);
      contentBytes = concatBytes.clone();
      return contentBytes;
    }
}
