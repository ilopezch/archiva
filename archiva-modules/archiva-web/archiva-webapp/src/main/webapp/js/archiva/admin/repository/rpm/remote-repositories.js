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
define("archiva/admin/repository/rpm/remote-repositories",["jquery","i18n","jquery.tmpl","bootstrap","jquery.validate","knockout","knockout.simpleGrid"],
function(jquery,i18n,jqueryTmpl,bootstrap,jqueryValidate,ko) {

  var RPM_REMOTE_API_BASE = "restServices/v2/archiva/repositories/rpm/remote";

  RpmRemoteRepository=function(id,name,location,description,loginUser,loginPassword,checkPath,timeoutMs){
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

  mapRpmRemoteRepository=function(data){
    if (data==null){ return null; }
    return new RpmRemoteRepository(data.id,data.name,data.location,data.description,data.loginUser,data.loginPassword,data.checkPath,data.timeoutMs);
  }

  RpmRemoteRepositoryViewModel=function(rpmRemoteRepository,update,rpmRemoteRepositoriesViewModel){
    this.rpmRemoteRepository=rpmRemoteRepository;
    this.rpmRemoteRepositoriesViewModel=rpmRemoteRepositoriesViewModel;
    this.update=update;
    var self=this;

    this.save=function(){
      var valid=$("#main-content").find("#rpm-remote-repository-edit-form").valid();
      if (valid==false){ return; }
      clearUserMessages();
      var userMessages=$("#user-messages");
      userMessages.html(mediumSpinnerImg());
      $("#rpm-remote-repository-save-button").button('loading');

      var payload={
        id: self.rpmRemoteRepository.id(),
        name: self.rpmRemoteRepository.name(),
        location: self.rpmRemoteRepository.location(),
        description: self.rpmRemoteRepository.description(),
        loginUser: self.rpmRemoteRepository.loginUser(),
        loginPassword: self.rpmRemoteRepository.loginPassword(),
        checkPath: self.rpmRemoteRepository.checkPath(),
        timeoutMs: self.rpmRemoteRepository.timeoutMs()
      };

      if (self.update){
        $.ajax(RPM_REMOTE_API_BASE+"/"+encodeURIComponent(self.rpmRemoteRepository.id()),{
          type:"PUT",
          contentType:"application/json",
          data:JSON.stringify(payload),
          dataType:"json",
          success:function(){
            displaySuccessMessage($.i18n.prop('rpm.remote.repository.updated',self.rpmRemoteRepository.id()));
            activateRpmRemoteRepositoriesGridTab();
            self.rpmRemoteRepository.modified(false);
          },
          error:function(data){
            var res=$.parseJSON(data.responseText);
            displayRestError(res);
          },
          complete:function(){
            $("#rpm-remote-repository-save-button").button('reset');
            removeMediumSpinnerImg(userMessages);
          }
        });
      } else {
        $.ajax(RPM_REMOTE_API_BASE,{
          type:"POST",
          contentType:"application/json",
          data:JSON.stringify(payload),
          dataType:"json",
          success:function(data){
            var created=mapRpmRemoteRepository(data);
            created.modified(false);
            self.rpmRemoteRepositoriesViewModel.rpmRemoteRepositories.push(created);
            displaySuccessMessage($.i18n.prop('rpm.remote.repository.added',created.id()));
            activateRpmRemoteRepositoriesGridTab();
          },
          error:function(data){
            var res=$.parseJSON(data.responseText);
            displayRestError(res);
          },
          complete:function(){
            $("#rpm-remote-repository-save-button").button('reset');
            removeMediumSpinnerImg(userMessages);
          }
        });
      }
    };

    displayGrid=function(){
      activateRpmRemoteRepositoriesGridTab();
    };
  }

  activateRpmRemoteRepositoriesGridTab=function(){
    var mainContent=$("#main-content");
    mainContent.find("#rpm-remote-repository-edit-li").removeClass("active");
    mainContent.find("#rpm-remote-repository-edit").removeClass("active");
    mainContent.find("#rpm-remote-repositories-view-li").addClass("active");
    mainContent.find("#rpm-remote-repositories-view").addClass("active");
    mainContent.find("#rpm-remote-repository-edit-li a").html($.i18n.prop("add"));
  }

  activateRpmRemoteRepositoryEditTab=function(){
    var mainContent=$("#main-content");
    mainContent.find("#rpm-remote-repositories-view-li").removeClass("active");
    mainContent.find("#rpm-remote-repositories-view").removeClass("active");
    mainContent.find("#rpm-remote-repository-edit-li").addClass("active");
    mainContent.find("#rpm-remote-repository-edit").addClass("active");
  }

  RpmRemoteRepositoriesViewModel=function(){
    this.rpmRemoteRepositories=ko.observableArray([]);
    this.gridViewModel=null;
    var self=this;

    editRpmRemoteRepository=function(rpmRemoteRepository){
      var mainContent=$("#main-content");
      var viewModel=new RpmRemoteRepositoryViewModel(rpmRemoteRepository,true,self);
      ko.applyBindings(viewModel,mainContent.find("#rpm-remote-repository-edit").get(0));
      activateRpmRemoteRepositoryEditTab();
      mainContent.find("#rpm-remote-repository-edit-li a").html($.i18n.prop('edit'));
      activateRpmRemoteRepositoryFormValidation();
    }

    removeRpmRemoteRepository=function(rpmRemoteRepository){
      clearUserMessages();
      openDialogConfirm(
        function(){
          var dialogText=$("#dialog-confirm-modal-body-text");
          dialogText.html(mediumSpinnerImg());
          $.ajax(RPM_REMOTE_API_BASE+"/"+encodeURIComponent(rpmRemoteRepository.id()),{
            type:"DELETE",
            success:function(){
              self.rpmRemoteRepositories.remove(rpmRemoteRepository);
              displaySuccessMessage($.i18n.prop("rpm.remote.repository.deleted",rpmRemoteRepository.name()));
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
        $.i18n.prop("rpm.remote.repository.delete.confirm",rpmRemoteRepository.name()),
        $("#rpm-remote-repository-delete-warning-tmpl").tmpl(rpmRemoteRepository)
      );
    }
  }

  activateRpmRemoteRepositoryFormValidation=function(){
    $("#main-content").find("#rpm-remote-repository-edit-form").validate({
      rules:{
        id:{ required:true },
        name:{ required:true },
        location:{ required:true, url:true }
      },
      showErrors:function(validator,errorMap,errorList){
        customShowError("#main-content #rpm-remote-repository-edit-form",validator,errorMap,errorMap);
      }
    });
  }

  displayRpmRemoteRepositoriesGrid=function(){
    var mainContent=$("#main-content");
    mainContent.html($("#rpmRemoteRepositoriesMain").tmpl());
    var rpmRemoteRepositoriesViewModel=new RpmRemoteRepositoriesViewModel();

    $.ajax(RPM_REMOTE_API_BASE,{
      type:"GET",
      dataType:"json",
      success:function(data){
        var repos=$.map(data.data,function(item){ return mapRpmRemoteRepository(item); });
        rpmRemoteRepositoriesViewModel.rpmRemoteRepositories(repos);
        rpmRemoteRepositoriesViewModel.gridViewModel=new ko.simpleGrid.viewModel({
          data:rpmRemoteRepositoriesViewModel.rpmRemoteRepositories,
          columns:[
            {headerText:$.i18n.prop('identifier'),rowText:"id"},
            {headerText:$.i18n.prop('name'),rowText:"name"},
            {headerText:$.i18n.prop('rpm.remote.repository.url'),rowText:"location"}
          ],
          pageSize:10
        });
        ko.applyBindings(rpmRemoteRepositoriesViewModel,mainContent.find("#rpm-remote-repositories-view").get(0));

        var addViewModel=new RpmRemoteRepositoryViewModel(new RpmRemoteRepository("","","","","","","",0),false,rpmRemoteRepositoriesViewModel);
        ko.applyBindings(addViewModel,mainContent.find("#rpm-remote-repository-edit").get(0));
        activateRpmRemoteRepositoryFormValidation();
      },
      error:function(data){
        var res=$.parseJSON(data.responseText);
        displayRestError(res);
      }
    });
  }

});
