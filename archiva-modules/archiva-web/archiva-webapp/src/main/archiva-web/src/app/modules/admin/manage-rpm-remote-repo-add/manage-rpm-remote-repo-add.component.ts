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
import {RpmRepositoryService} from '@app/services/rpm-repository.service';
import {RpmRemoteRepository} from '@app/model/rpm-remote-repository';
import {ToastService} from '@app/services/toast.service';
import {ErrorResult} from '@app/model/error-result';
import {catchError} from 'rxjs/operators';

@Component({
    selector: 'app-manage-rpm-remote-repo-add',
    templateUrl: './manage-rpm-remote-repo-add.component.html'
})
export class ManageRpmRemoteRepoAddComponent implements OnInit {

    @ViewChild('successTmpl') public successTmpl: TemplateRef<any>;
    @ViewChild('errorTmpl') public errorTmpl: TemplateRef<any>;

    repoForm: FormGroup;
    success = false;
    error = false;
    errorResult: ErrorResult;
    result: RpmRemoteRepository;

    constructor(private fb: FormBuilder,
                private rpmService: RpmRepositoryService,
                private toastService: ToastService) {
    }

    ngOnInit(): void {
        this.repoForm = this.fb.group({
            id: ['', [Validators.required, Validators.pattern('^[a-zA-Z0-9._-]+$')]],
            name: ['', Validators.required],
            description: [''],
            location: ['', Validators.required],
            login_user: [''],
            login_password: [''],
            check_path: ['repodata/repomd.xml'],
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

        const repo = Object.assign(new RpmRemoteRepository(), this.repoForm.value);
        this.rpmService.addRemoteRepository(repo).pipe(
            catchError((err: ErrorResult) => {
                this.errorResult = err;
                this.success = false;
                this.error = true;
                this.toastService.showError('rpm-remote-repo-add', this.errorTmpl, {contextData: err});
                return [];
            })
        ).subscribe((created: RpmRemoteRepository) => {
            this.result = created;
            this.success = true;
            this.error = false;
            this.toastService.showSuccess('rpm-remote-repo-add', this.successTmpl, {contextData: created});
            this.repoForm.reset({
                check_path: 'repodata/repomd.xml',
                timeout_ms: 60000
            });
        });
    }
}
