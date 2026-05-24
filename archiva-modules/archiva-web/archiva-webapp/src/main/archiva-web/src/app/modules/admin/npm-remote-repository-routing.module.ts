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
import {RouterModule, Routes} from '@angular/router';
import {RoutingGuardService as Guard} from '@app/services/routing-guard.service';
import {ManageNpmRemoteRepoComponent} from './manage-npm-remote-repo/manage-npm-remote-repo.component';
import {ManageNpmRemoteRepoListComponent} from './manage-npm-remote-repo-list/manage-npm-remote-repo-list.component';
import {ManageNpmRemoteRepoAddComponent} from './manage-npm-remote-repo-add/manage-npm-remote-repo-add.component';
import {ManageNpmRemoteRepoEditComponent} from './manage-npm-remote-repo-edit/manage-npm-remote-repo-edit.component';

const routes: Routes = [
    {
        path: '',
        component: ManageNpmRemoteRepoComponent,
        canActivate: [Guard],
        data: {perm: 'menu.admin.config'},
        children: [
            {path: 'list', component: ManageNpmRemoteRepoListComponent},
            {path: 'add', component: ManageNpmRemoteRepoAddComponent},
            {path: 'edit/:id', component: ManageNpmRemoteRepoEditComponent},
            {path: '', redirectTo: 'list', pathMatch: 'full'}
        ]
    }
];

@NgModule({
    imports: [RouterModule.forChild(routes)],
    exports: []
})
export class NpmRemoteRepositoryRoutingModule {
}
