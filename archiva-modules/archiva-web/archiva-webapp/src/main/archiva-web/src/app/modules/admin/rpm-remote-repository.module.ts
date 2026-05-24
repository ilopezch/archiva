/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import {NgModule} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule, ReactiveFormsModule} from '@angular/forms';
import {SharedModule} from '../shared/shared.module';
import {RpmRemoteRepositoryRoutingModule} from './rpm-remote-repository-routing.module';
import {ManageRpmRemoteRepoComponent} from './manage-rpm-remote-repo/manage-rpm-remote-repo.component';
import {ManageRpmRemoteRepoListComponent} from './manage-rpm-remote-repo-list/manage-rpm-remote-repo-list.component';
import {ManageRpmRemoteRepoAddComponent} from './manage-rpm-remote-repo-add/manage-rpm-remote-repo-add.component';
import {ManageRpmRemoteRepoEditComponent} from './manage-rpm-remote-repo-edit/manage-rpm-remote-repo-edit.component';

@NgModule({
    declarations: [
        ManageRpmRemoteRepoComponent,
        ManageRpmRemoteRepoListComponent,
        ManageRpmRemoteRepoAddComponent,
        ManageRpmRemoteRepoEditComponent
    ],
    imports: [
        CommonModule,
        SharedModule,
        RpmRemoteRepositoryRoutingModule,
        FormsModule,
        ReactiveFormsModule
    ]
})
export class RpmRemoteRepositoryModule {
}
