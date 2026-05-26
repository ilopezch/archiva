/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
define("archiva/admin/repository/npm/remote-repositories",["jquery","i18n","jquery.tmpl","bootstrap","jquery.validate","knockout","knockout.simpleGrid"],
function(jquery,i18n,jqueryTmpl,bootstrap,jqueryValidate,ko) {

  var NPM_REMOTE_API_BASE = "restServices/v2/archiva/repositories/npm/remote";

  NpmRemoteRepository=function(id,name,location,description,loginUser,loginPassword,checkPath,timeoutMs){
    var self=this;

    this.id=ko.observable(id);
    this.id.subscribe(function(){ self.modified(true); });

    this.name=ko.observable(name);
    this.name.subscribe(function(){ self.modified(true); });

    this.location=ko.observable(location);
    this.location.subscribe(function(){ self.modified(true); });

    this.description=ko.observable(description);
    this.description.subscribe(function(){ self.modified(true); });

    this.loginUser=ko.observable(loginUser);
    this.loginUser.subscribe(function(){ self.modified(true); });

    this.loginPassword=ko.observable(loginPassword);
    this.loginPassword.subscribe(function(){ self.modified(true); });

    this.checkPath=ko.observable(checkPath);
    this.checkPath.subscribe(function(){ self.modified(true); });

    this.timeoutMs=ko.observable(timeoutMs!=null?timeoutMs:0);
    this.timeoutMs.subscribe(function(){ self.modified(true); });

    this.modified=ko.observable(false);
  }

  mapNpmRemoteRepository=function(data){
    if (data==null){ return null; }
    return new NpmRemoteRepository(data.id,data.name,data.location,data.description,data.loginUser,data.loginPassword,data.checkPath,data.timeoutMs);
  }

  NpmRemoteRepositoryViewModel=function(npmRemoteRepository,update,npmRemoteRepositoriesViewModel){
    this.npmRemoteRepository=npmRemoteRepository;
    this.npmRemoteRepositoriesViewModel=npmRemoteRepositoriesViewModel;
    this.update=update;
    var self=this;

    this.save=function(){
      var valid=$("#main-content").find("#npm-remote-repository-edit-form").valid();
      if (valid==false){ return; }
      clearUserMessages();
      var userMessages=$("#user-messages");
      userMessages.html(mediumSpinnerImg());
      $("#npm-remote-repository-save-button").button('loading');

      var payload={
        id: self.npmRemoteRepository.id(),
        name: self.npmRemoteRepository.name(),
        location: self.npmRemoteRepository.location(),
        description: self.npmRemoteRepository.description(),
        loginUser: self.npmRemoteRepository.loginUser(),
        loginPassword: self.npmRemoteRepository.loginPassword(),
        checkPath: self.npmRemoteRepository.checkPath(),
        timeoutMs: self.npmRemoteRepository.timeoutMs()
      };

      if (self.update){
        $.ajax(NPM_REMOTE_API_BASE+"/"+encodeURIComponent(self.npmRemoteRepository.id()),{
          type:"PUT",
          contentType:"application/json",
          data:JSON.stringify(payload),
          dataType:"json",
          success:function(){
            displaySuccessMessage($.i18n.prop('npm.remote.repository.updated',self.npmRemoteRepository.id()));
            activateNpmRemoteRepositoriesGridTab();
            self.npmRemoteRepository.modified(false);
          },
          error:function(data){
            var res=$.parseJSON(data.responseText);
            displayRestError(res);
          },
          complete:function(){
            $("#npm-remote-repository-save-button").button('reset');
            removeMediumSpinnerImg(userMessages);
          }
        });
      } else {
        $.ajax(NPM_REMOTE_API_BASE,{
          type:"POST",
          contentType:"application/json",
          data:JSON.stringify(payload),
          dataType:"json",
          success:function(data){
            var created=mapNpmRemoteRepository(data);
            created.modified(false);
            self.npmRemoteRepositoriesViewModel.npmRemoteRepositories.push(created);
            displaySuccessMessage($.i18n.prop('npm.remote.repository.added',created.id()));
            activateNpmRemoteRepositoriesGridTab();
          },
          error:function(data){
            var res=$.parseJSON(data.responseText);
            displayRestError(res);
          },
          complete:function(){
            $("#npm-remote-repository-save-button").button('reset');
            removeMediumSpinnerImg(userMessages);
          }
        });
      }
    };

    displayGrid=function(){
      activateNpmRemoteRepositoriesGridTab();
    };
  }

  activateNpmRemoteRepositoriesGridTab=function(){
    var mainContent=$("#main-content");
    mainContent.find("#npm-remote-repository-edit-li").removeClass("active");
    mainContent.find("#npm-remote-repository-edit").removeClass("active");
    mainContent.find("#npm-remote-repositories-view-li").addClass("active");
    mainContent.find("#npm-remote-repositories-view").addClass("active");
    mainContent.find("#npm-remote-repository-edit-li a").html($.i18n.prop("add"));
  }

  activateNpmRemoteRepositoryEditTab=function(){
    var mainContent=$("#main-content");
    mainContent.find("#npm-remote-repositories-view-li").removeClass("active");
    mainContent.find("#npm-remote-repositories-view").removeClass("active");
    mainContent.find("#npm-remote-repository-edit-li").addClass("active");
    mainContent.find("#npm-remote-repository-edit").addClass("active");
  }

  NpmRemoteRepositoriesViewModel=function(){
    this.npmRemoteRepositories=ko.observableArray([]);
    this.gridViewModel=null;
    var self=this;

    editNpmRemoteRepository=function(npmRemoteRepository){
      var mainContent=$("#main-content");
      var viewModel=new NpmRemoteRepositoryViewModel(npmRemoteRepository,true,self);
      ko.applyBindings(viewModel,mainContent.find("#npm-remote-repository-edit").get(0));
      activateNpmRemoteRepositoryEditTab();
      mainContent.find("#npm-remote-repository-edit-li a").html($.i18n.prop('edit'));
      activateNpmRemoteRepositoryFormValidation();
    }

    removeNpmRemoteRepository=function(npmRemoteRepository){
      clearUserMessages();
      openDialogConfirm(
        function(){
          var dialogText=$("#dialog-confirm-modal-body-text");
          dialogText.html(mediumSpinnerImg());
          $.ajax(NPM_REMOTE_API_BASE+"/"+encodeURIComponent(npmRemoteRepository.id()),{
            type:"DELETE",
            success:function(){
              self.npmRemoteRepositories.remove(npmRemoteRepository);
              displaySuccessMessage($.i18n.prop("npm.remote.repository.deleted",npmRemoteRepository.name()));
            },
            error:function(data){
              var res=$.parseJSON(data.responseText);
              displayRestError(res);
            },
            complete:function(){
              removeMediumSpinnerImg(dialogText);
              closeDialogConfirm();
            }
          });
        },
        $.i18n.prop("ok"),
        $.i18n.prop("cancel"),
        $.i18n.prop("npm.remote.repository.delete.confirm",npmRemoteRepository.name()),
        $("#npm-remote-repository-delete-warning-tmpl").tmpl(npmRemoteRepository)
      );
    }
  }

  activateNpmRemoteRepositoryFormValidation=function(){
    $("#main-content").find("#npm-remote-repository-edit-form").validate({
      rules:{
        id:{ required:true },
        name:{ required:true },
        location:{ required:true, url:true }
      },
      showErrors:function(validator,errorMap,errorList){
        customShowError("#main-content #npm-remote-repository-edit-form",validator,errorMap,errorMap);
      }
    });
  }

  displayNpmRemoteRepositoriesGrid=function(){
    var mainContent=$("#main-content");
    mainContent.html($("#npmRemoteRepositoriesMain").tmpl());
    var npmRemoteRepositoriesViewModel=new NpmRemoteRepositoriesViewModel();

    $.ajax(NPM_REMOTE_API_BASE,{
      type:"GET",
      dataType:"json",
      success:function(data){
        var repos=$.map(data.data,function(item){ return mapNpmRemoteRepository(item); });
        npmRemoteRepositoriesViewModel.npmRemoteRepositories(repos);
        npmRemoteRepositoriesViewModel.gridViewModel=new ko.simpleGrid.viewModel({
          data:npmRemoteRepositoriesViewModel.npmRemoteRepositories,
          columns:[
            {headerText:$.i18n.prop('identifier'),rowText:"id"},
            {headerText:$.i18n.prop('name'),rowText:"name"},
            {headerText:$.i18n.prop('npm.remote.repository.url'),rowText:"location"}
          ],
          pageSize:10
        });
        ko.applyBindings(npmRemoteRepositoriesViewModel,mainContent.find("#npm-remote-repositories-view").get(0));

        var addViewModel=new NpmRemoteRepositoryViewModel(new NpmRemoteRepository("","","","","","","",0),false,npmRemoteRepositoriesViewModel);
        ko.applyBindings(addViewModel,mainContent.find("#npm-remote-repository-edit").get(0));
        activateNpmRemoteRepositoryFormValidation();
      },
      error:function(data){
        var res=$.parseJSON(data.responseText);
        displayRestError(res);
      }
    });
  }

});
