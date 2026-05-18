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
import {NpmManagedRepository} from '@app/model/npm-managed-repository';
import {PagedResult} from '@app/model/paged-result';
import {ErrorResult} from '@app/model/error-result';

@Injectable({
    providedIn: 'root'
})
export class NpmRepositoryService {

    private readonly BASE = 'repositories/npm/managed';

    constructor(private rest: ArchivaRequestService) {
    }

    query(searchTerm: string, offset: number, limit: number,
          orderBy: string[], order: string): Observable<PagedResult<NpmManagedRepository>> {
        return this.rest.executeRestCall<PagedResult<NpmManagedRepository>>('get', 'archiva', this.BASE, {
            q: searchTerm,
            offset: offset,
            limit: limit,
            orderBy: orderBy,
            order: order
        });
    }

    getRepository(id: string): Observable<NpmManagedRepository> {
        return this.rest.executeRestCall<NpmManagedRepository>('get', 'archiva', `${this.BASE}/${id}`, null).pipe(
            catchError((error: HttpErrorResponse) =>
                throwError(this.rest.getTranslatedErrorResult(error)))
        );
    }

    addRepository(repo: NpmManagedRepository): Observable<NpmManagedRepository> {
        return this.rest.executeResponseCall<NpmManagedRepository>('post', 'archiva', this.BASE, repo).pipe(
            catchError((error: HttpErrorResponse) =>
                throwError(this.rest.getTranslatedErrorResult(error))),
            map((response: HttpResponse<NpmManagedRepository>) => response.body)
        );
    }

    updateRepository(id: string, repo: NpmManagedRepository): Observable<NpmManagedRepository> {
        return this.rest.executeRestCall<NpmManagedRepository>('put', 'archiva', `${this.BASE}/${id}`, repo).pipe(
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
}
