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
define("archiva/admin/repository/npm/main",["jquery","i18n","archiva/admin/repository/npm/repositories","archiva/admin/repository/npm/remote-repositories"],
        function() {
            showMenu = function(administrationMenuItems) {
                administrationMenuItems.push({
                    text: $.i18n.prop('menu.npm.repositories'),
                    order: 515,
                    id: "menu-npm-repositories-list-a",
                    href: "#npmrepositorylist",
                    redback: "{permissions: ['archiva-manage-configuration']}",
                    func: function() {
                        displayNpmRepositoriesGrid();
                    }
                });
                administrationMenuItems.push({
                    text: $.i18n.prop('menu.npm.remote.repositories'),
                    order: 515.5,
                    id: "menu-npm-remote-repositories-list-a",
                    href: "#npmremoterepositorylist",
                    redback: "{permissions: ['archiva-manage-configuration']}",
                    func: function() {
                        displayNpmRemoteRepositoriesGrid();
                    }
                });
            };
        }
);
