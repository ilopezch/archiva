package org.apache.archiva.rpm.repository.repodata;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.util.ArrayList;
import java.util.List;

/**
 * Metadata extracted from an RPM binary header, used to generate repodata XML.
 * All fields map directly to RPM header tags read by {@link RpmHeaderParser}.
 */
public class RpmPackageInfo
{
    // Coordinates
    public String name;
    public String version;
    public String release;
    public String epoch;        // null or "0" when absent
    public String arch;

    // Description
    public String summary;
    public String description;
    public String license;
    public String group;
    public String url;
    public String vendor;
    public String packager;
    public String sourceRpm;

    // Size / time
    public long installedSize;  // TAG_SIZE (1009)
    public long archiveSize;    // TAG_ARCHIVESIZE (1046) — may be 0 if absent
    public long buildTime;      // TAG_BUILDTIME (1006) — Unix timestamp

    // Checksums (populated by RepomdGenerator after reading the file)
    public String sha256;       // hex SHA-256 of the RPM file
    public String md5;          // hex MD5 of the RPM file (for older clients)
    public long   fileSize;     // on-disk file size in bytes
    public String location;     // relative path within the repository root

    // Dependencies (parallel arrays)
    public List<String> requireNames    = new ArrayList<>();
    public List<String> requireVersions = new ArrayList<>();
    public List<Integer> requireFlags   = new ArrayList<>();

    public List<String> provideNames    = new ArrayList<>();
    public List<String> provideVersions = new ArrayList<>();
    public List<Integer> provideFlags   = new ArrayList<>();

    // File list basenames (TAG_BASENAMES 1117)
    public List<String> files = new ArrayList<>();

    // Directory info for full path reconstruction (TAG_DIRNAMES 1118, TAG_DIRINDEXES 1119)
    public List<String>  dirNames   = new ArrayList<>();
    public List<Integer> dirIndexes = new ArrayList<>();

    // Changelog entries — parallel arrays (TAG_CHANGELOGTIME 1080, TAG_CHANGELOGNAME 1081, TAG_CHANGELOGTEXT 1082)
    public List<Long>   changelogTimes = new ArrayList<>();
    public List<String> changelogNames = new ArrayList<>();
    public List<String> changelogTexts = new ArrayList<>();

    public String pkgId()
    {
        return sha256 != null ? sha256 : md5;
    }

    public String evr()
    {
        String e = ( epoch != null && !epoch.isEmpty() && !"0".equals( epoch ) ) ? epoch + ":" : "";
        return e + version + "-" + release;
    }
}
