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

import {Component, OnInit, TemplateRef, ViewChild} from '@angular/core';
import {Observable} from 'rxjs';
import {RpmManagedRepository} from '@app/model/rpm-managed-repository';
import {RpmRepositoryService} from '@app/services/rpm-repository.service';
import {PagedResult} from '@app/model/paged-result';
import {EntityService} from '@app/model/entity-service';
import {ToastService} from '@app/services/toast.service';
import {ErrorResult} from '@app/model/error-result';
import {catchError} from 'rxjs/operators';

@Component({
    selector: 'app-manage-rpm-repo-list',
    templateUrl: './manage-rpm-repo-list.component.html'
})
export class ManageRpmRepoListComponent implements OnInit {

    @ViewChild('deletedTmpl') public deletedTmpl: TemplateRef<any>;
    @ViewChild('errorTmpl') public errorTmpl: TemplateRef<any>;

    service: EntityService<RpmManagedRepository>;
    sortField = ['id'];
    sortOrder = 'asc';

    constructor(private rpmService: RpmRepositoryService, private toastService: ToastService) {
        this.service = (searchTerm: string, offset: number, limit: number,
                        orderBy: string[], order: string): Observable<PagedResult<RpmManagedRepository>> =>
            rpmService.query(searchTerm, offset, limit, orderBy, order);
    }

    ngOnInit(): void {}

    deleteRepository(repo: RpmManagedRepository): void {
        this.rpmService.deleteRepository(repo.id).pipe(
            catchError((error: ErrorResult) => {
                this.toastService.showError('rpm-repo-list', this.errorTmpl, {contextData: error});
                return [];
            })
        ).subscribe(() => {
            this.toastService.showSuccess('rpm-repo-list', this.deletedTmpl, {contextData: repo});
        });
    }
}
