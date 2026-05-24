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
define("archiva/admin/repository/npm/repositories",["jquery","i18n","jquery.tmpl","bootstrap","jquery.validate","knockout","knockout.simpleGrid"],
function(jquery,i18n,jqueryTmpl,bootstrap,jqueryValidate,ko) {

  var NPM_API_BASE = "restServices/v2/archiva/repositories/npm/managed";

  NpmManagedRepository=function(id,name,location,description,scanned,schedulingDefinition){
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

    this.modified=ko.observable(false);
  }

  mapNpmManagedRepository=function(data){
    if (data==null){ return null; }
    return new NpmManagedRepository(data.id,data.name,data.location,data.description,data.scanned,data.schedulingDefinition);
  }

  NpmManagedRepositoryViewModel=function(npmRepository,update,npmRepositoriesViewModel){
    this.npmRepository=npmRepository;
    this.npmRepositoriesViewModel=npmRepositoriesViewModel;
    this.update=update;
    var self=this;

    this.save=function(){
      var valid=$("#main-content").find("#npm-repository-edit-form").valid();
      if (valid==false){ return; }
      clearUserMessages();
      var userMessages=$("#user-messages");
      userMessages.html(mediumSpinnerImg());
      $("#npm-repository-save-button").button('loading');

      var payload={
        id: self.npmRepository.id(),
        name: self.npmRepository.name(),
        location: self.npmRepository.location(),
        description: self.npmRepository.description(),
        scanned: self.npmRepository.scanned(),
        schedulingDefinition: self.npmRepository.schedulingDefinition()
      };

      if (self.update){
        $.ajax(NPM_API_BASE+"/"+encodeURIComponent(self.npmRepository.id()),{
          type:"PUT",
          contentType:"application/json",
          data:JSON.stringify(payload),
          dataType:"json",
          success:function(){
            displaySuccessMessage($.i18n.prop('npm.repository.updated',self.npmRepository.id()));
            activateNpmRepositoriesGridTab();
            self.npmRepository.modified(false);
          },
          error:function(data){
            var res=$.parseJSON(data.responseText);
            displayRestError(res);
          },
          complete:function(){
            $("#npm-repository-save-button").button('reset');
            removeMediumSpinnerImg(userMessages);
          }
        });
      } else {
        $.ajax(NPM_API_BASE,{
          type:"POST",
          contentType:"application/json",
          data:JSON.stringify(payload),
          dataType:"json",
          success:function(data){
            var created=mapNpmManagedRepository(data);
            created.modified(false);
            self.npmRepositoriesViewModel.npmRepositories.push(created);
            displaySuccessMessage($.i18n.prop('npm.repository.added',created.id()));
            activateNpmRepositoriesGridTab();
          },
          error:function(data){
            var res=$.parseJSON(data.responseText);
            displayRestError(res);
          },
          complete:function(){
            $("#npm-repository-save-button").button('reset');
            removeMediumSpinnerImg(userMessages);
          }
        });
      }
    };

    displayGrid=function(){
      activateNpmRepositoriesGridTab();
    };
  }

  activateNpmRepositoriesGridTab=function(){
    var mainContent=$("#main-content");
    mainContent.find("#npm-repository-edit-li").removeClass("active");
    mainContent.find("#npm-repository-edit").removeClass("active");
    mainContent.find("#npm-repositories-view-li").addClass("active");
    mainContent.find("#npm-repositories-view").addClass("active");
    mainContent.find("#npm-repository-edit-li a").html($.i18n.prop("add"));
  }

  activateNpmRepositoryEditTab=function(){
    var mainContent=$("#main-content");
    mainContent.find("#npm-repositories-view-li").removeClass("active");
    mainContent.find("#npm-repositories-view").removeClass("active");
    mainContent.find("#npm-repository-edit-li").addClass("active");
    mainContent.find("#npm-repository-edit").addClass("active");
  }

  NpmManagedRepositoriesViewModel=function(){
    this.npmRepositories=ko.observableArray([]);
    this.gridViewModel=null;
    var self=this;

    editNpmRepository=function(npmRepository){
      var mainContent=$("#main-content");
      var viewModel=new NpmManagedRepositoryViewModel(npmRepository,true,self);
      ko.applyBindings(viewModel,mainContent.find("#npm-repository-edit").get(0));
      activateNpmRepositoryEditTab();
      mainContent.find("#npm-repository-edit-li a").html($.i18n.prop('edit'));
      activateNpmRepositoryFormValidation();
    }

    removeNpmRepository=function(npmRepository){
      clearUserMessages();
      openDialogConfirm(
        function(){
          var dialogText=$("#dialog-confirm-modal-body-text");
          dialogText.html(mediumSpinnerImg());
          $.ajax(NPM_API_BASE+"/"+encodeURIComponent(npmRepository.id()),{
            type:"DELETE",
            success:function(){
              self.npmRepositories.remove(npmRepository);
              displaySuccessMessage($.i18n.prop("npm.repository.deleted",npmRepository.name()));
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
        $.i18n.prop("npm.repository.delete.confirm",npmRepository.name()),
        $("#npm-repository-delete-warning-tmpl").tmpl(npmRepository)
      );
    }
  }

  activateNpmRepositoryFormValidation=function(){
    var validator=$("#main-content").find("#npm-repository-edit-form").validate({
      rules:{
        id:{ required:true },
        name:{ required:true },
        location:{ required:true }
      },
      showErrors:function(validator,errorMap,errorList){
        customShowError("#main-content #npm-repository-edit-form",validator,errorMap,errorMap);
      }
    });
  }

  displayNpmRepositoriesGrid=function(){
    var mainContent=$("#main-content");
    mainContent.html($("#npmRepositoriesMain").tmpl());
    var npmRepositoriesViewModel=new NpmManagedRepositoriesViewModel();

    $.ajax(NPM_API_BASE,{
      type:"GET",
      dataType:"json",
      success:function(data){
        var repos=$.map(data.data,function(item){ return mapNpmManagedRepository(item); });
        npmRepositoriesViewModel.npmRepositories(repos);
        npmRepositoriesViewModel.gridViewModel=new ko.simpleGrid.viewModel({
          data:npmRepositoriesViewModel.npmRepositories,
          columns:[
            {headerText:$.i18n.prop('identifier'),rowText:"id"},
            {headerText:$.i18n.prop('name'),rowText:"name"},
            {headerText:$.i18n.prop('directory'),rowText:"location"}
          ],
          pageSize:10
        });
        ko.applyBindings(npmRepositoriesViewModel,mainContent.find("#npm-repositories-view").get(0));

        var addViewModel=new NpmManagedRepositoryViewModel(new NpmManagedRepository("","","","",true,""),false,npmRepositoriesViewModel);
        ko.applyBindings(addViewModel,mainContent.find("#npm-repository-edit").get(0));
        activateNpmRepositoryFormValidation();
      },
      error:function(data){
        var res=$.parseJSON(data.responseText);
        displayRestError(res);
      }
    });
  }

});
