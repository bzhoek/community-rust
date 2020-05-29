/**
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.elegoff.plugins.rust.clippy;

import javax.annotation.Nullable;
import java.io.*;
import java.util.stream.Stream;
import java.util.function.Consumer;

import org.sonarsource.analyzer.commons.internal.json.simple.JSONArray;
import org.sonarsource.analyzer.commons.internal.json.simple.JSONObject;
import org.sonarsource.analyzer.commons.internal.json.simple.parser.JSONParser;
import org.sonarsource.analyzer.commons.internal.json.simple.parser.ParseException;

import static java.nio.charset.StandardCharsets.UTF_8;

public class ClippyJsonReportReader {
    private final JSONParser jsonParser = new JSONParser();
    private final Consumer<ClippyIssue> consumer;
    private static final String RESULTS="results";
    private static final String BEGINJSON = "{\""+ RESULTS+ "\": [";
    private static final String ENDJSON = "]}";
    private static final String VISIT_MSG="for further information visit";
    private static final String MESSAGE="message";

    public static class ClippyIssue {
        @Nullable
        String filePath;
        @Nullable
        String ruleKey;
        @Nullable
        String message;
        @Nullable
        Integer lineNumberStart;
        @Nullable
        Integer lineNumberEnd;
        @Nullable
        Integer colNumberStart;
        @Nullable
        Integer colNumberEnd;
        @Nullable
        String severity;
    }

    private ClippyJsonReportReader(Consumer<ClippyIssue> consumer) {
        this.consumer = consumer;
    }

    static void read(InputStream in, Consumer<ClippyIssue> consumer) throws IOException, ParseException {
        new ClippyJsonReportReader(consumer).read(in);
    }

    private void read(InputStream in) throws IOException, ParseException {
        JSONObject rootObject = (JSONObject) jsonParser.parse(new InputStreamReader(in, UTF_8));
        JSONArray results = (JSONArray) rootObject.get(RESULTS);
        if (results != null) {
            ((Stream<JSONObject>) results.stream()).forEach(this::onResult);
        }
    }

    private void onResult(JSONObject result) {


        ClippyIssue clippyIssue = new ClippyIssue();
        //Exit silently when JSON is not compliant

        JSONObject message = (JSONObject) result.get(MESSAGE);
        if (message == null) return;
        JSONObject code = (JSONObject) message.get("code");
        if (code == null) return;
        clippyIssue.ruleKey = (String) code.get("code");
        JSONArray spans = (JSONArray) message.get("spans");
        if ((spans == null) || spans.isEmpty()) return;
        JSONObject span = (JSONObject) spans.get(0);
        clippyIssue.filePath = (String) span.get("file_name");
        clippyIssue.message = (String) message.get(MESSAGE);
        JSONArray children = (JSONArray) message.get("children");
        if ((clippyIssue.message != null)&&(children != null) && !children.isEmpty()){
            addHelpDetails(clippyIssue, children);
        }
        clippyIssue.lineNumberStart = toInteger(span.get("line_start"));
        clippyIssue.lineNumberEnd = toInteger(span.get("line_end"));
        clippyIssue.colNumberStart = toInteger(span.get("column_start"));
        clippyIssue.colNumberEnd = toInteger(span.get("column_end"));
        clippyIssue.severity = (String) message.get("level");

        consumer.accept(clippyIssue);
    }

    private void addHelpDetails(ClippyIssue clippyIssue, JSONArray children) {
        int sz = children.size();
        StringBuilder sb = new StringBuilder(clippyIssue.message);
        for (int i = 0;i< sz;i++){
            JSONObject child = (JSONObject)children.get(i);
            String level = (String)child.get("level");
            String childMsg = (String)child.get(MESSAGE);

            //ignore some of the children
            boolean isNote = level.equalsIgnoreCase("note");
            boolean isVisitLink = childMsg.startsWith(VISIT_MSG);
            if (isNote || isVisitLink) continue;

            sb.append("\n").append(childMsg);

            //Are there any suggested replacement ?
            String replStr = suggestedMessage(child);
            if (replStr != null) sb.append("\n").append(replStr);

        }
        clippyIssue.message = sb.toString();
    }

    private static String suggestedMessage(JSONObject obj){
        if (obj == null) return null;
        JSONArray spans = (JSONArray)obj.get("spans");
        if ((spans == null) || spans.isEmpty()) return null;
        JSONObject span = (JSONObject)spans.get(0);
        return (String)span.get("suggested_replacement");
    }

    private static Integer toInteger(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return null;
    }

    public static InputStream toJSON(File rawReport) throws IOException {


        if (rawReport == null) {
            throw new FileNotFoundException();
        }

        StringBuilder sb = new StringBuilder(BEGINJSON);

        //read text report line by line
        String reportPath = rawReport.getAbsolutePath();
        BufferedReader reader;

        try (BufferedReader bufferedReader = reader = new BufferedReader(new FileReader(reportPath))) {
            String line = reader.readLine();
            String separator = "";
            while (line != null) {
                //a valid Clippy result needs to be a valid json String
                if (line.startsWith("{") && line.endsWith("}")) {
                    sb.append(separator).append(line);
                    separator = ",";
                }
                line = reader.readLine();
            }
        }
        reader.close();
        sb.append(ENDJSON);
        return new ByteArrayInputStream(sb.toString().getBytes());

    }

}
