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
import {switchMap, catchError} from 'rxjs/operators';
import {RpmRepositoryService} from '@app/services/rpm-repository.service';
import {RpmGpgKeyInfo, RpmManagedRepository} from '@app/model/rpm-managed-repository';
import {ToastService} from '@app/services/toast.service';
import {ErrorResult} from '@app/model/error-result';

@Component({
    selector: 'app-manage-rpm-repo-edit',
    templateUrl: './manage-rpm-repo-edit.component.html'
})
export class ManageRpmRepoEditComponent implements OnInit {

    @ViewChild('successTmpl') public successTmpl: TemplateRef<any>;
    @ViewChild('errorTmpl') public errorTmpl: TemplateRef<any>;

    repoForm: FormGroup;
    repoId: string;
    success = false;
    error = false;
    errorResult: ErrorResult;
    result: RpmManagedRepository;

    gpgKey: RpmGpgKeyInfo = null;
    gpgKeyLoading = false;
    gpgKeyRotating = false;
    gpgKeyError: string = null;

    constructor(private route: ActivatedRoute,
                private fb: FormBuilder,
                private rpmService: RpmRepositoryService,
                private toastService: ToastService) {
    }

    ngOnInit(): void {
        this.repoForm = this.fb.group({
            id: [{value: '', disabled: true}],
            name: ['', Validators.required],
            description: [''],
            location: ['', Validators.required],
            scanned: [false],
            scheduling_definition: ['0 0 * * * ?'],
            gpg_key_path: [''],
            gpg_user_id: ['']
        });

        this.route.params.pipe(
            switchMap(params => {
                this.repoId = params['id'];
                return this.rpmService.getRepository(this.repoId);
            })
        ).subscribe((repo: RpmManagedRepository) => {
            this.repoForm.patchValue(repo);
            this.loadGpgKey();
        });
    }

    loadGpgKey(): void {
        this.gpgKeyLoading = true;
        this.gpgKeyError = null;
        this.rpmService.getGpgKey(this.repoId).subscribe({
            next: (info: RpmGpgKeyInfo) => {
                this.gpgKey = info;
                this.gpgKeyLoading = false;
            },
            error: (err) => {
                this.gpgKeyError = err?.message || 'Failed to load GPG key';
                this.gpgKeyLoading = false;
            }
        });
    }

    downloadPublicKey(): void {
        if (!this.gpgKey) return;
        const blob = new Blob([this.gpgKey.armoredPublicKey], {type: 'application/pgp-keys'});
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `${this.repoId}-repokey.gpg`;
        a.click();
        URL.revokeObjectURL(url);
    }

    rotateGpgKey(): void {
        if (!confirm('Generate a new GPG key? Clients will need to re-import the public key.')) return;
        this.gpgKeyRotating = true;
        this.gpgKeyError = null;
        this.rpmService.rotateGpgKey(this.repoId).subscribe({
            next: (info: RpmGpgKeyInfo) => {
                this.gpgKey = info;
                this.gpgKeyRotating = false;
            },
            error: (err) => {
                this.gpgKeyError = err?.message || 'Key rotation failed';
                this.gpgKeyRotating = false;
            }
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

        const repo = Object.assign(new RpmManagedRepository(), this.repoForm.getRawValue());
        this.rpmService.updateRepository(this.repoId, repo).pipe(
            catchError((err: ErrorResult) => {
                this.errorResult = err;
                this.success = false;
                this.error = true;
                this.toastService.showError('rpm-repo-edit', this.errorTmpl, {contextData: err});
                return [];
            })
        ).subscribe((updated: RpmManagedRepository) => {
            this.result = updated;
            this.success = true;
            this.error = false;
            this.toastService.showSuccess('rpm-repo-edit', this.successTmpl, {contextData: updated});
        });
    }
}
