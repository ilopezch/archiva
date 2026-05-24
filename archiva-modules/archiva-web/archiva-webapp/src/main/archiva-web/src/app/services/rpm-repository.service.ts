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

import {Injectable} from '@angular/core';
import {HttpErrorResponse, HttpResponse} from '@angular/common/http';
import {Observable, throwError} from 'rxjs';
import {catchError, map} from 'rxjs/operators';
import {ArchivaRequestService} from './archiva-request.service';
import {RpmGpgKeyInfo, RpmManagedRepository} from '@app/model/rpm-managed-repository';
import {RpmRemoteRepository} from '@app/model/rpm-remote-repository';
import {PagedResult} from '@app/model/paged-result';

@Injectable({
    providedIn: 'root'
})
export class RpmRepositoryService {

    private readonly BASE = 'repositories/rpm/managed';
    private readonly REMOTE_BASE = 'repositories/rpm/remote';

    constructor(private rest: ArchivaRequestService) {
    }

    query(searchTerm: string, offset: number, limit: number,
          orderBy: string[], order: string): Observable<PagedResult<RpmManagedRepository>> {
        return this.rest.executeRestCall<PagedResult<RpmManagedRepository>>('get', 'archiva', this.BASE, {
            q: searchTerm,
            offset: offset,
            limit: limit,
            orderBy: orderBy,
            order: order
        });
    }

    getRepository(id: string): Observable<RpmManagedRepository> {
        return this.rest.executeRestCall<RpmManagedRepository>('get', 'archiva', `${this.BASE}/${id}`, null).pipe(
            catchError((error: HttpErrorResponse) =>
                throwError(this.rest.getTranslatedErrorResult(error)))
        );
    }

    addRepository(repo: RpmManagedRepository): Observable<RpmManagedRepository> {
        return this.rest.executeResponseCall<RpmManagedRepository>('post', 'archiva', this.BASE, repo).pipe(
            catchError((error: HttpErrorResponse) =>
                throwError(this.rest.getTranslatedErrorResult(error))),
            map((response: HttpResponse<RpmManagedRepository>) => response.body)
        );
    }

    updateRepository(id: string, repo: RpmManagedRepository): Observable<RpmManagedRepository> {
        return this.rest.executeRestCall<RpmManagedRepository>('put', 'archiva', `${this.BASE}/${id}`, repo).pipe(
            catchError((error: HttpErrorResponse) =>
                throwError(this.rest.getTranslatedErrorResult(error)))
        );
    }

    deleteRepository(id: string, deleteContent: boolean = false): Observable<boolean> {
        return this.rest.executeResponseCall<boolean>(
            'delete', 'archiva', `${this.BASE}/${id}`, {deleteContent: deleteContent}
        ).pipe(
            catchError((error: HttpErrorResponse) =>
                throwError(this.rest.getTranslatedErrorResult(error))),
            map((response: HttpResponse<boolean>) => response.status === 200)
        );
    }

    getGpgKey(id: string): Observable<RpmGpgKeyInfo> {
        return this.rest.executeRestCall<RpmGpgKeyInfo>('get', 'archiva', `${this.BASE}/${id}/gpgkey`, null).pipe(
            catchError((error: HttpErrorResponse) =>
                throwError(this.rest.getTranslatedErrorResult(error)))
        );
    }

    rotateGpgKey(id: string): Observable<RpmGpgKeyInfo> {
        return this.rest.executeRestCall<RpmGpgKeyInfo>('post', 'archiva', `${this.BASE}/${id}/gpgkey/rotate`, null).pipe(
            catchError((error: HttpErrorResponse) =>
                throwError(this.rest.getTranslatedErrorResult(error)))
        );
    }

    queryRemote(searchTerm: string, offset: number, limit: number,
                orderBy: string[], order: string): Observable<PagedResult<RpmRemoteRepository>> {
        return this.rest.executeRestCall<PagedResult<RpmRemoteRepository>>('get', 'archiva', this.REMOTE_BASE, {
            q: searchTerm,
            offset: offset,
            limit: limit,
            orderBy: orderBy,
            order: order
        });
    }

    getRemoteRepository(id: string): Observable<RpmRemoteRepository> {
        return this.rest.executeRestCall<RpmRemoteRepository>('get', 'archiva', `${this.REMOTE_BASE}/${id}`, null).pipe(
            catchError((error: HttpErrorResponse) =>
                throwError(this.rest.getTranslatedErrorResult(error)))
        );
    }

    addRemoteRepository(repo: RpmRemoteRepository): Observable<RpmRemoteRepository> {
        return this.rest.executeResponseCall<RpmRemoteRepository>('post', 'archiva', this.REMOTE_BASE, repo).pipe(
            catchError((error: HttpErrorResponse) =>
                throwError(this.rest.getTranslatedErrorResult(error))),
            map((response: HttpResponse<RpmRemoteRepository>) => response.body)
        );
    }

    updateRemoteRepository(id: string, repo: RpmRemoteRepository): Observable<RpmRemoteRepository> {
        return this.rest.executeRestCall<RpmRemoteRepository>('put', 'archiva', `${this.REMOTE_BASE}/${id}`, repo).pipe(
            catchError((error: HttpErrorResponse) =>
                throwError(this.rest.getTranslatedErrorResult(error)))
        );
    }

    deleteRemoteRepository(id: string): Observable<boolean> {
        return this.rest.executeResponseCall<boolean>(
            'delete', 'archiva', `${this.REMOTE_BASE}/${id}`, null
        ).pipe(
            catchError((error: HttpErrorResponse) =>
                throwError(this.rest.getTranslatedErrorResult(error))),
            map((response: HttpResponse<boolean>) => response.status === 200)
        );
    }
}
