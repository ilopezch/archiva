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
import {NpmRepositoryService} from '@app/services/npm-repository.service';
import {NpmManagedRepository} from '@app/model/npm-managed-repository';
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
                return this.npmService.getRepository(this.repoId);
            })
        ).subscribe((repo: NpmManagedRepository) => {
            this.repoForm.patchValue(repo);
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
