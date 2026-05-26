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
import {ActivatedRoute} from '@angular/router';
import {FormBuilder, FormGroup, Validators} from '@angular/forms';
import {switchMap} from 'rxjs/operators';
import {catchError} from 'rxjs/operators';
import {forkJoin} from 'rxjs';
import {NpmRepositoryService} from '@app/services/npm-repository.service';
import {NpmManagedRepository} from '@app/model/npm-managed-repository';
import {NpmRemoteRepository} from '@app/model/npm-remote-repository';
import {ToastService} from '@app/services/toast.service';
import {ErrorResult} from '@app/model/error-result';

@Component({
    selector: 'app-manage-npm-repo-edit',
    templateUrl: './manage-npm-repo-edit.component.html'
})
export class ManageNpmRepoEditComponent implements OnInit {

    @ViewChild('successTmpl') public successTmpl: TemplateRef<any>;
    @ViewChild('errorTmpl') public errorTmpl: TemplateRef<any>;

    repoForm: FormGroup;
    repoId: string;
    success = false;
    error = false;
    errorResult: ErrorResult;
    result: NpmManagedRepository;

    proxyConnectors: string[] = [];
    availableRemoteRepos: NpmRemoteRepository[] = [];
    selectedRemoteId = '';
    proxyError: string = null;

    constructor(private route: ActivatedRoute,
                private fb: FormBuilder,
                private npmService: NpmRepositoryService,
                private toastService: ToastService) {
    }

    ngOnInit(): void {
        this.repoForm = this.fb.group({
            id: [{value: '', disabled: true}],
            name: ['', Validators.required],
            description: [''],
            location: ['', Validators.required],
            scanned: [false],
            scheduling_definition: ['0 0 * * * ?']
        });

        this.route.params.pipe(
            switchMap(params => {
                this.repoId = params['id'];
                return forkJoin({
                    repo: this.npmService.getRepository(this.repoId),
                    connectors: this.npmService.getProxyConnectors(this.repoId),
                    remotes: this.npmService.queryRemote('', 0, 100, ['id'], 'asc')
                });
            })
        ).subscribe(({repo, connectors, remotes}) => {
            this.repoForm.patchValue(repo);
            this.proxyConnectors = connectors || [];
            this.availableRemoteRepos = (remotes?.data || []);
        });
    }

    get unlinkedRemoteRepos(): NpmRemoteRepository[] {
        return this.availableRemoteRepos.filter(r => !this.proxyConnectors.includes(r.id));
    }

    addProxyConnector(): void {
        if (!this.selectedRemoteId) return;
        this.proxyError = null;
        this.npmService.addProxyConnector(this.repoId, this.selectedRemoteId).subscribe({
            next: () => {
                this.proxyConnectors = [...this.proxyConnectors, this.selectedRemoteId];
                this.selectedRemoteId = '';
            },
            error: (err) => { this.proxyError = err?.message || 'Failed to add mirror source'; }
        });
    }

    removeProxyConnector(remoteId: string): void {
        this.proxyError = null;
        this.npmService.deleteProxyConnector(this.repoId, remoteId).subscribe({
            next: () => {
                this.proxyConnectors = this.proxyConnectors.filter(id => id !== remoteId);
            },
            error: (err) => { this.proxyError = err?.message || 'Failed to remove mirror source'; }
        });
    }

    valid(field: string): string[] {
        const ctrl = this.repoForm.get(field);
        if (ctrl == null) return ['form-control'];
        return ctrl.invalid && (ctrl.dirty || ctrl.touched)
            ? ['form-control', 'is-invalid']
            : ['form-control'];
    }

    onSubmit(): void {
        this.result = null;
        if (!this.repoForm.valid) return;

        const repo = Object.assign(new NpmManagedRepository(), this.repoForm.getRawValue());
        this.npmService.updateRepository(this.repoId, repo).pipe(
            catchError((err: ErrorResult) => {
                this.errorResult = err;
                this.success = false;
                this.error = true;
                this.toastService.showError('npm-repo-edit', this.errorTmpl, {contextData: err});
                return [];
            })
        ).subscribe((updated: NpmManagedRepository) => {
            this.result = updated;
            this.success = true;
            this.error = false;
            this.toastService.showSuccess('npm-repo-edit', this.successTmpl, {contextData: updated});
        });
    }
}
