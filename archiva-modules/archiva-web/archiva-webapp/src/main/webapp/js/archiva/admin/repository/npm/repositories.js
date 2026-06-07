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
  var NPM_REMOTE_API_BASE = "restServices/v2/archiva/repositories/npm/remote";
  var NPM_TOKEN_API_BASE = "restServices/v2/archiva/npm/tokens";

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

    this.displayGrid=function(){
      activateNpmRepositoriesGridTab();
    };

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

    showNpmTokenPanel=function(npmRepository){
      var mainContent=$("#main-content");
      var panel=mainContent.find("#npm-token-panel");
      var repoId=npmRepository.id();
      panel.find("#npm-token-repo-id").text(repoId);
      panel.find("#npm-token-label").val("");
      panel.find("#npm-token-err").hide();
      panel.find("#npm-token-result").hide();
      panel.show();

      function buildSnippet(token){
        var pageBase=window.location.href.replace(/#.*$/,"").replace(/[^\/]+$/,"");
        var registryUrl=pageBase+"npm/"+repoId+"/";
        var hostPart=registryUrl.replace(/^https?:/,"");
        return hostPart+":_authToken="+token+"\nregistry="+registryUrl;
      }

      function formatTimestamp(ms){
        return ms ? new Date(ms).toLocaleString() : $.i18n.prop("npm.token.never");
      }

      function loadTokens(){
        var list=panel.find("#npm-token-list");
        list.empty().append(mediumSpinnerImg());
        $.ajax(NPM_TOKEN_API_BASE,{
          type:"GET",
          dataType:"json",
          success:function(tokens){
            list.empty();
            if (!tokens || tokens.length===0){
              list.append($("<p>").addClass("muted").text($.i18n.prop("npm.token.none")));
              return;
            }
            var table=$("<table>").addClass("table table-striped table-condensed");
            var headerRow=$("<tr>");
            $.each(["npm.token.label","npm.token.created","npm.token.lastused"],function(i,key){
              headerRow.append($("<th>").text($.i18n.prop(key)));
            });
            headerRow.append($("<th>"));
            table.append($("<thead>").append(headerRow));
            var tbody=$("<tbody>");
            $.each(tokens,function(i,t){
              var label=t.label?t.label:$.i18n.prop("npm.token.unlabeled");
              var tr=$("<tr>");
              tr.append($("<td>").text(label));
              tr.append($("<td>").text(formatTimestamp(t.createdAt)));
              tr.append($("<td>").text(formatTimestamp(t.lastUsedAt)));
              var revokeBtn=$("<button>").addClass("btn btn-mini btn-danger").text($.i18n.prop("npm.token.revoke"));
              revokeBtn.on("click",function(){
                openDialogConfirm(
                  function(){
                    $.ajax(NPM_TOKEN_API_BASE+"/"+encodeURIComponent(t.id),{
                      type:"DELETE",
                      success:function(){
                        displaySuccessMessage($.i18n.prop("npm.token.revoked",label));
                        loadTokens();
                      },
                      error:function(data){
                        var res=$.parseJSON(data.responseText);
                        displayRestError(res);
                      },
                      complete:function(){
                        closeDialogConfirm();
                      }
                    });
                  },
                  $.i18n.prop("ok"),
                  $.i18n.prop("cancel"),
                  $.i18n.prop("npm.token.revoke.confirm",label)
                );
              });
              tr.append($("<td>").append(revokeBtn));
              tbody.append(tr);
            });
            table.append(tbody);
            list.append(table);
          },
          error:function(data){
            list.empty();
            var res=$.parseJSON(data.responseText);
            displayRestError(res);
          }
        });
      }

      panel.find("#npm-token-generate-btn").off("click").on("click",function(){
        panel.find("#npm-token-err").hide();
        panel.find("#npm-token-result").hide();
        var label=panel.find("#npm-token-label").val().trim();
        $.ajax(NPM_TOKEN_API_BASE,{
          type:"POST",
          contentType:"application/json",
          data:JSON.stringify({label:label}),
          dataType:"json",
          success:function(created){
            panel.find("#npm-token-snippet").val(buildSnippet(created.token));
            panel.find("#npm-token-result").show();
            panel.find("#npm-token-label").val("");
            loadTokens();
          },
          error:function(data){
            var res=$.parseJSON(data.responseText);
            displayRestError(res);
          }
        });
      });

      panel.find("#npm-token-copy-btn").off("click").on("click",function(){
        var ta=panel.find("#npm-token-snippet").get(0);
        ta.select();
        document.execCommand("copy");
        var btn=$(this);
        btn.text($.i18n.prop("npm.token.copied"));
        setTimeout(function(){ btn.text($.i18n.prop("npm.token.copy")); },2000);
      });

      panel.find("#npm-token-close-btn").off("click").on("click",function(){
        panel.hide();
      });

      loadTokens();
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

  displayNpmAllRepositoriesGrid=function(){
    var mainContent=$("#main-content");
    mainContent.html($("#npmAllRepositoriesMain").tmpl());
    mainContent.find("#npm-repositories-tabs a:first").tab("show");

    mainContent.find("#npm-managed-repositories-content").append(mediumSpinnerImg());
    mainContent.find("#npm-remote-repositories-content").append(mediumSpinnerImg());

    var npmRepositoriesViewModel=new NpmManagedRepositoriesViewModel();
    var npmRemoteRepositoriesViewModel=new NpmRemoteRepositoriesViewModel();

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
        removeMediumSpinnerImg(mainContent.find("#npm-managed-repositories-content"));
      },
      error:function(data){
        var res=$.parseJSON(data.responseText);
        displayRestError(res);
      }
    });

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
        removeMediumSpinnerImg(mainContent.find("#npm-remote-repositories-content"));

        var hasRegistry=$.grep(repos,function(r){
          return r.location()==="https://registry.npmjs.org/";
        }).length>0;
        if (!hasRegistry){
          var registryPayload={
            id:"npm-registry",
            name:"NPM Registry",
            location:"https://registry.npmjs.org/",
            description:"Central NPM Registry",
            loginUser:"",
            loginPassword:"",
            checkPath:"",
            timeoutMs:0
          };
          $.ajax(NPM_REMOTE_API_BASE,{
            type:"POST",
            contentType:"application/json",
            data:JSON.stringify(registryPayload),
            dataType:"json",
            success:function(created){
              var repo=mapNpmRemoteRepository(created);
              repo.modified(false);
              npmRemoteRepositoriesViewModel.npmRemoteRepositories.push(repo);
              displaySuccessMessage($.i18n.prop('npm.registry.added'));
            },
            error:function(){}
          });
        }
      },
      error:function(data){
        var res=$.parseJSON(data.responseText);
        displayRestError(res);
      }
    });

    mainContent.find("#npm-repositories-pills").on('show',function(e){
      if ($(e.target).attr("href")==="#npm-repository-edit"){
        var addEl=mainContent.find("#npm-repository-edit").get(0);
        ko.cleanNode(addEl);
        var addViewModel=new NpmManagedRepositoryViewModel(new NpmManagedRepository("","","","",true,""),false,npmRepositoriesViewModel);
        ko.applyBindings(addViewModel,addEl);
        activateNpmRepositoryFormValidation();
      }
      if ($(e.target).attr("href")==="#npm-repositories-view"){
        mainContent.find("#npm-repository-edit-li a").html($.i18n.prop("add"));
      }
    });

    mainContent.find("#npm-remote-repositories-pills").on('show',function(e){
      if ($(e.target).attr("href")==="#npm-remote-repository-edit"){
        var addEl=mainContent.find("#npm-remote-repository-edit").get(0);
        ko.cleanNode(addEl);
        var addViewModel=new NpmRemoteRepositoryViewModel(new NpmRemoteRepository("","","","","","","",0),false,npmRemoteRepositoriesViewModel);
        ko.applyBindings(addViewModel,addEl);
        activateNpmRemoteRepositoryFormValidation();
      }
      if ($(e.target).attr("href")==="#npm-remote-repositories-view"){
        mainContent.find("#npm-remote-repository-edit-li a").html($.i18n.prop("add"));
      }
    });
  }

});
