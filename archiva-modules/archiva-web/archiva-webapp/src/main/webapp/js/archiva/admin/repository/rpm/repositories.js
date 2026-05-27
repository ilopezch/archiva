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
define("archiva/admin/repository/rpm/repositories",["jquery","i18n","jquery.tmpl","bootstrap","jquery.validate","knockout","knockout.simpleGrid"],
function(jquery,i18n,jqueryTmpl,bootstrap,jqueryValidate,ko) {

  var RPM_API_BASE = "restServices/v2/archiva/repositories/rpm/managed";
  var RPM_REMOTE_API_BASE = "restServices/v2/archiva/repositories/rpm/remote";

  RpmManagedRepository=function(id,name,location,description,scanned,schedulingDefinition,gpgKeyPath,gpgUserId){
    var self=this;

    this.id=ko.observable(id);
    this.id.subscribe(function(){ self.modified(true); });

    this.name=ko.observable(name);
    this.name.subscribe(function(){ self.modified(true); });

    this.location=ko.observable(location);
    this.location.subscribe(function(){ self.modified(true); });

    this.description=ko.observable(description);
    this.description.subscribe(function(){ self.modified(true); });

    this.scanned=ko.observable(scanned!=null?scanned:true);
    this.scanned.subscribe(function(){ self.modified(true); });

    this.schedulingDefinition=ko.observable(schedulingDefinition);
    this.schedulingDefinition.subscribe(function(){ self.modified(true); });

    this.gpgKeyPath=ko.observable(gpgKeyPath);
    this.gpgKeyPath.subscribe(function(){ self.modified(true); });

    this.gpgUserId=ko.observable(gpgUserId);
    this.gpgUserId.subscribe(function(){ self.modified(true); });

    this.modified=ko.observable(false);
  }

  RpmGpgKeyInfo=function(fingerprint,userId,algorithm,bitStrength,created,expires,armoredPublicKey){
    this.fingerprint=ko.observable(fingerprint);
    this.userId=ko.observable(userId);
    this.algorithm=ko.observable(algorithm);
    this.bitStrength=ko.observable(bitStrength);
    this.created=ko.observable(created);
    this.expires=ko.observable(expires);
    this.armoredPublicKey=ko.observable(armoredPublicKey);
  }

  mapRpmManagedRepository=function(data){
    if (data==null){ return null; }
    return new RpmManagedRepository(data.id,data.name,data.location,data.description,data.scanned,data.schedulingDefinition,data.gpgKeyPath,data.gpgUserId);
  }

  mapRpmGpgKeyInfo=function(data){
    if (data==null){ return null; }
    return new RpmGpgKeyInfo(data.fingerprint,data.userId,data.algorithm,data.bitStrength,data.created,data.expires,data.armoredPublicKey);
  }

  RpmManagedRepositoryViewModel=function(rpmRepository,update,rpmRepositoriesViewModel){
    this.rpmRepository=rpmRepository;
    this.rpmRepositoriesViewModel=rpmRepositoriesViewModel;
    this.update=update;
    this.gpgKeyInfo=ko.observable(null);
    var self=this;

    if (update && rpmRepository.id()){
      $.ajax(RPM_API_BASE+"/"+encodeURIComponent(rpmRepository.id())+"/gpgkey",{
        type:"GET",
        dataType:"json",
        success:function(data){
          self.gpgKeyInfo(mapRpmGpgKeyInfo(data));
        }
      });
    }

    this.displayGrid=function(){
      activateRpmRepositoriesGridTab();
    };

    this.save=function(){
      var valid=$("#main-content").find("#rpm-repository-edit-form").valid();
      if (valid==false){ return; }
      clearUserMessages();
      var userMessages=$("#user-messages");
      userMessages.html(mediumSpinnerImg());
      $("#rpm-repository-save-button").button('loading');

      var payload={
        id: self.rpmRepository.id(),
        name: self.rpmRepository.name(),
        location: self.rpmRepository.location(),
        description: self.rpmRepository.description(),
        scanned: self.rpmRepository.scanned(),
        schedulingDefinition: self.rpmRepository.schedulingDefinition(),
        gpgKeyPath: self.rpmRepository.gpgKeyPath(),
        gpgUserId: self.rpmRepository.gpgUserId()
      };

      if (self.update){
        $.ajax(RPM_API_BASE+"/"+encodeURIComponent(self.rpmRepository.id()),{
          type:"PUT",
          contentType:"application/json",
          data:JSON.stringify(payload),
          dataType:"json",
          success:function(){
            displaySuccessMessage($.i18n.prop('rpm.repository.updated',self.rpmRepository.id()));
            activateRpmRepositoriesGridTab();
            self.rpmRepository.modified(false);
          },
          error:function(data){
            var res=$.parseJSON(data.responseText);
            displayRestError(res);
          },
          complete:function(){
            $("#rpm-repository-save-button").button('reset');
            removeMediumSpinnerImg(userMessages);
          }
        });
      } else {
        $.ajax(RPM_API_BASE,{
          type:"POST",
          contentType:"application/json",
          data:JSON.stringify(payload),
          dataType:"json",
          success:function(data){
            var created=mapRpmManagedRepository(data);
            created.modified(false);
            self.rpmRepositoriesViewModel.rpmRepositories.push(created);
            displaySuccessMessage($.i18n.prop('rpm.repository.added',created.id()));
            activateRpmRepositoriesGridTab();
          },
          error:function(data){
            var res=$.parseJSON(data.responseText);
            displayRestError(res);
          },
          complete:function(){
            $("#rpm-repository-save-button").button('reset');
            removeMediumSpinnerImg(userMessages);
          }
        });
      }
    };

    this.rotateGpgKey=function(){
      clearUserMessages();
      var userMessages=$("#user-messages");
      userMessages.html(mediumSpinnerImg());
      $.ajax(RPM_API_BASE+"/"+encodeURIComponent(self.rpmRepository.id())+"/gpgkey/rotate",{
        type:"POST",
        dataType:"json",
        success:function(data){
          self.gpgKeyInfo(mapRpmGpgKeyInfo(data));
          displaySuccessMessage($.i18n.prop('rpm.repository.gpgkey.rotated',self.rpmRepository.id()));
        },
        error:function(data){
          var res=$.parseJSON(data.responseText);
          displayRestError(res);
        },
        complete:function(){
          removeMediumSpinnerImg(userMessages);
        }
      });
    };

  }

  activateRpmRepositoriesGridTab=function(){
    var mainContent=$("#main-content");
    mainContent.find("#rpm-repository-edit-li").removeClass("active");
    mainContent.find("#rpm-repository-edit").removeClass("active");
    mainContent.find("#rpm-repositories-view-li").addClass("active");
    mainContent.find("#rpm-repositories-view").addClass("active");
    mainContent.find("#rpm-repository-edit-li a").html($.i18n.prop("add"));
  }

  activateRpmRepositoryEditTab=function(){
    var mainContent=$("#main-content");
    mainContent.find("#rpm-repositories-view-li").removeClass("active");
    mainContent.find("#rpm-repositories-view").removeClass("active");
    mainContent.find("#rpm-repository-edit-li").addClass("active");
    mainContent.find("#rpm-repository-edit").addClass("active");
  }

  RpmManagedRepositoriesViewModel=function(){
    this.rpmRepositories=ko.observableArray([]);
    this.gridViewModel=null;
    var self=this;

    editRpmRepository=function(rpmRepository){
      var mainContent=$("#main-content");
      var viewModel=new RpmManagedRepositoryViewModel(rpmRepository,true,self);
      ko.applyBindings(viewModel,mainContent.find("#rpm-repository-edit").get(0));
      activateRpmRepositoryEditTab();
      mainContent.find("#rpm-repository-edit-li a").html($.i18n.prop('edit'));
      activateRpmRepositoryFormValidation();
    }

    removeRpmRepository=function(rpmRepository){
      clearUserMessages();
      openDialogConfirm(
        function(){
          var dialogText=$("#dialog-confirm-modal-body-text");
          dialogText.html(mediumSpinnerImg());
          $.ajax(RPM_API_BASE+"/"+encodeURIComponent(rpmRepository.id()),{
            type:"DELETE",
            success:function(){
              self.rpmRepositories.remove(rpmRepository);
              displaySuccessMessage($.i18n.prop("rpm.repository.deleted",rpmRepository.name()));
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
        $.i18n.prop("rpm.repository.delete.confirm",rpmRepository.name()),
        $("#rpm-repository-delete-warning-tmpl").tmpl(rpmRepository)
      );
    }
  }

  activateRpmRepositoryFormValidation=function(){
    $("#main-content").find("#rpm-repository-edit-form").validate({
      rules:{
        id:{ required:true },
        name:{ required:true },
        location:{ required:true }
      },
      showErrors:function(validator,errorMap,errorList){
        customShowError("#main-content #rpm-repository-edit-form",validator,errorMap,errorMap);
      }
    });
  }

  displayRpmRepositoriesGrid=function(){
    var mainContent=$("#main-content");
    mainContent.html($("#rpmRepositoriesMain").tmpl());
    var rpmRepositoriesViewModel=new RpmManagedRepositoriesViewModel();

    $.ajax(RPM_API_BASE,{
      type:"GET",
      dataType:"json",
      success:function(data){
        var repos=$.map(data.data,function(item){ return mapRpmManagedRepository(item); });
        rpmRepositoriesViewModel.rpmRepositories(repos);
        rpmRepositoriesViewModel.gridViewModel=new ko.simpleGrid.viewModel({
          data:rpmRepositoriesViewModel.rpmRepositories,
          columns:[
            {headerText:$.i18n.prop('identifier'),rowText:"id"},
            {headerText:$.i18n.prop('name'),rowText:"name"},
            {headerText:$.i18n.prop('directory'),rowText:"location"}
          ],
          pageSize:10
        });
        ko.applyBindings(rpmRepositoriesViewModel,mainContent.find("#rpm-repositories-view").get(0));

        var addViewModel=new RpmManagedRepositoryViewModel(new RpmManagedRepository("","","","",true,"","",""),false,rpmRepositoriesViewModel);
        ko.applyBindings(addViewModel,mainContent.find("#rpm-repository-edit").get(0));
        activateRpmRepositoryFormValidation();
      },
      error:function(data){
        var res=$.parseJSON(data.responseText);
        displayRestError(res);
      }
    });
  }

  displayRpmAllRepositoriesGrid=function(){
    var mainContent=$("#main-content");
    mainContent.html($("#rpmAllRepositoriesMain").tmpl());
    mainContent.find("#rpm-repositories-tabs a:first").tab("show");

    mainContent.find("#rpm-managed-repositories-content").append(mediumSpinnerImg());
    mainContent.find("#rpm-remote-repositories-content").append(mediumSpinnerImg());

    var rpmRepositoriesViewModel=new RpmManagedRepositoriesViewModel();
    var rpmRemoteRepositoriesViewModel=new RpmRemoteRepositoriesViewModel();

    $.ajax(RPM_API_BASE,{
      type:"GET",
      dataType:"json",
      success:function(data){
        var repos=$.map(data.data,function(item){ return mapRpmManagedRepository(item); });
        rpmRepositoriesViewModel.rpmRepositories(repos);
        rpmRepositoriesViewModel.gridViewModel=new ko.simpleGrid.viewModel({
          data:rpmRepositoriesViewModel.rpmRepositories,
          columns:[
            {headerText:$.i18n.prop('identifier'),rowText:"id"},
            {headerText:$.i18n.prop('name'),rowText:"name"},
            {headerText:$.i18n.prop('directory'),rowText:"location"}
          ],
          pageSize:10
        });
        ko.applyBindings(rpmRepositoriesViewModel,mainContent.find("#rpm-repositories-view").get(0));
        removeMediumSpinnerImg(mainContent.find("#rpm-managed-repositories-content"));
      },
      error:function(data){
        var res=$.parseJSON(data.responseText);
        displayRestError(res);
      }
    });

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
        removeMediumSpinnerImg(mainContent.find("#rpm-remote-repositories-content"));
      },
      error:function(data){
        var res=$.parseJSON(data.responseText);
        displayRestError(res);
      }
    });

    mainContent.find("#rpm-repositories-pills").on('show',function(e){
      if ($(e.target).attr("href")==="#rpm-repository-edit"){
        var addViewModel=new RpmManagedRepositoryViewModel(new RpmManagedRepository("","","","",true,"","",""),false,rpmRepositoriesViewModel);
        ko.applyBindings(addViewModel,mainContent.find("#rpm-repository-edit").get(0));
        activateRpmRepositoryFormValidation();
      }
      if ($(e.target).attr("href")==="#rpm-repositories-view"){
        mainContent.find("#rpm-repository-edit-li a").html($.i18n.prop("add"));
      }
    });

    mainContent.find("#rpm-remote-repositories-pills").on('show',function(e){
      if ($(e.target).attr("href")==="#rpm-remote-repository-edit"){
        var addViewModel=new RpmRemoteRepositoryViewModel(new RpmRemoteRepository("","","","","","","",0),false,rpmRemoteRepositoriesViewModel);
        ko.applyBindings(addViewModel,mainContent.find("#rpm-remote-repository-edit").get(0));
        activateRpmRemoteRepositoryFormValidation();
      }
      if ($(e.target).attr("href")==="#rpm-remote-repositories-view"){
        mainContent.find("#rpm-remote-repository-edit-li a").html($.i18n.prop("add"));
      }
    });
  }

});
