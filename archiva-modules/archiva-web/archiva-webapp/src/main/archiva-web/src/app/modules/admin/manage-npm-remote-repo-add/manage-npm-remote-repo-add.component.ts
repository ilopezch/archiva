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
import {FormBuilder, FormGroup, Validators} from '@angular/forms';
import {NpmRepositoryService} from '@app/services/npm-repository.service';
import {NpmRemoteRepository} from '@app/model/npm-remote-repository';
import {ToastService} from '@app/services/toast.service';
import {ErrorResult} from '@app/model/error-result';
import {catchError} from 'rxjs/operators';

@Component({
    selector: 'app-manage-npm-remote-repo-add',
    templateUrl: './manage-npm-remote-repo-add.component.html'
})
export class ManageNpmRemoteRepoAddComponent implements OnInit {

    @ViewChild('successTmpl') public successTmpl: TemplateRef<any>;
    @ViewChild('errorTmpl') public errorTmpl: TemplateRef<any>;

    repoForm: FormGroup;
    success = false;
    error = false;
    errorResult: ErrorResult;
    result: NpmRemoteRepository;

    constructor(private fb: FormBuilder,
                private npmService: NpmRepositoryService,
                private toastService: ToastService) {
    }

    ngOnInit(): void {
        this.repoForm = this.fb.group({
            id: ['', [Validators.required, Validators.pattern('^[a-zA-Z0-9._-]+$')]],
            name: ['', Validators.required],
            description: [''],
            location: ['https://registry.npmjs.org', Validators.required],
            login_user: [''],
            login_password: [''],
            check_path: [''],
            timeout_ms: [60000, [Validators.required, Validators.min(1000)]]
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

        const repo = Object.assign(new NpmRemoteRepository(), this.repoForm.value);
        this.npmService.addRemoteRepository(repo).pipe(
            catchError((err: ErrorResult) => {
                this.errorResult = err;
                this.success = false;
                this.error = true;
                this.toastService.showError('npm-remote-repo-add', this.errorTmpl, {contextData: err});
                return [];
            })
        ).subscribe((created: NpmRemoteRepository) => {
            this.result = created;
            this.success = true;
            this.error = false;
            this.toastService.showSuccess('npm-remote-repo-add', this.successTmpl, {contextData: created});
            this.repoForm.reset({
                location: 'https://registry.npmjs.org',
                timeout_ms: 60000
            });
        });
    }
}
