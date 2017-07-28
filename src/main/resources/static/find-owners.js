// Copyright (C) 2017 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

Gerrit.install(function(self) {
  function onFindOwners(c) {
    const HTML_BULLET = '<small>&#x2605;</small>'; // a Black Star
    const HTML_HAS_APPROVAL_HEADER =
        '<hr><b>Files with +1 or +2 Code-Review vote from owners:</b><br>';
    const HTML_IS_EXEMPTED =
        '<b>This commit is exempted from owner approval.</b><br>';
    const HTML_NEED_REVIEWER_HEADER =
        '<hr><b>Files without owner reviewer:</b><br>';
    const HTML_NEED_APPROVAL_HEADER =
        '<hr><b>Files without Code-Review vote from an owner:</b><br>';
    const HTML_NO_OWNER =
        '<b>No owner was found for changed files.</b><br>';
    const HTML_OWNERS_HEADER = '<hr><b>Owners in alphabetical order:</b><br>';
    const HTML_SELECT_REVIEWERS =
        '<b>Check the box before owner names to select reviewers, ' +
        'then click the "Apply" button.' +
        '</b><br><small>Each file needs at least one owner. ' +
        'Owners listed after a file are ordered by their importance. ' +
        '(Or declare "<b><span style="font-size:80%;">' +
        'Exempt-From-Owner-Approval:</span></b> ' +
        '<i>reasons...</i>" in the Commit Message.)</small><br>';

    const APPLY_BUTTON_ID = 'FindOwners:Apply';
    const CHECKBOX_ID = 'FindOwners:CheckBox';
    const HEADER_DIV_ID = 'FindOwners:Header';
    const OWNERS_DIV_ID = 'FindOwners:Owners';
    const HAS_APPROVAL_DIV_ID = 'FindOwners:HasApproval';
    const NEED_APPROVAL_DIV_ID = 'FindOwners:NeedApproval';
    const NEED_REVIEWER_DIV_ID = 'FindOwners:NeedReviewer';

    // Aliases to values in the context.
    const branch = c.change.branch;
    const changeId = c.change._number;
    const changeOwner = c.change.owner;
    const message = c.revision.commit.message;
    const project = c.change.project;

    var minVoteLevel = 1; // could be changed by server returned results.
    var reviewerId = {}; // map from a reviewer's email to account id.
    var reviewerVote = {}; // map from a reviewer's email to Code-Review vote.

    // addList and removeList are used only under applySelections.
    var addList = []; // remain emails to add to reviewers
    var removeList = []; // remain emails to remove from reviewers
    var needRefresh = false; // true if to refresh after checkAddRemoveLists

    function getElement(id) {
      return document.getElementById(id);
    }
    function getReviewers(change, callBack) {
      Gerrit.get('changes/' + change + '/reviewers', callBack);
    }
    function setupReviewersMap(reviewerList) {
      reviewerId = {};
      reviewerVote = {};
      reviewerList.forEach(function(reviewer) {
        if ('email' in reviewer && '_account_id' in reviewer) {
          reviewerId[reviewer.email] = reviewer._account_id;
          reviewerVote[reviewer.email] = 0;
          if ('approvals' in reviewer && 'Code-Review' in reviewer.approvals) {
            reviewerVote[reviewer.email] =
                parseInt(reviewer.approvals['Code-Review']);
            // The 'Code-Review' values could be "-2", "-1", " 0", "+1", "+2"
          }
        }
      });
      // Give CL author a default minVoteLevel vote.
      if (changeOwner != null &&
          'email' in changeOwner && '_account_id' in changeOwner &&
          (!(changeOwner.email in reviewerId) ||
           reviewerVote[changeOwner.email] == 0)) {
        reviewerId[changeOwner.email] = changeOwner._account_id;
        reviewerVote[changeOwner.email] = minVoteLevel;
      }
    }
    function checkAddRemoveLists() {
      // Gerrit.post and delete are asynchronous.
      // Do one at a time, with checkAddRemoveLists as callBack.
      for (var i = 0; i < addList.length; i++) {
        var email = addList[i];
        if (!(email in reviewerId)) {
          addList = addList.slice(i + 1, addList.length);
          // A post request can fail if given reviewer email is invalid.
          // Gerrit core UI shows the error dialog and does not provide
          // a way for plugins to handle the error yet.
          needRefresh = true;
          Gerrit.post('changes/' + changeId + '/reviewers',
                      {'reviewer': email},
                      checkAddRemoveLists);
          return;
        }
      }
      for (var i = 0; i < removeList.length; i++) {
        var email = removeList[i];
        if (email in reviewerId) {
          removeList = removeList.slice(i + 1, removeList.length);
          needRefresh = true;
          Gerrit.delete('changes/' + changeId +
                        '/reviewers/' + reviewerId[email],
                        checkAddRemoveLists);
          return;
        }
      }
      c.hide();
      if (needRefresh) {
        needRefresh = false;
        Gerrit.refresh();
      }
      callServer(showFindOwnersResults);
    }
    function applyGetReviewers(reviewerList) {
      setupReviewersMap(reviewerList);
      checkAddRemoveLists(); // update and pop up window at the end
    }
    function hasOwnerReviewer(reviewers, owners) {
      return owners.some(function(owner) {
        return (owner in reviewers || owner == '*');
      });
    }
    function hasOwnerApproval(votes, owners) {
      var foundApproval = false;
      for (var j = 0; j < owners.length; j++) {
        if (owners[j] in votes) {
          var v = votes[owners[j]];
          if (v < 0) {
            return false; // cannot have any negative vote
          }
          foundApproval |= v >= minVoteLevel;
        }
      }
      return foundApproval;
    }
    function isExemptedFromOwnerApproval() {
      return message.match(/(Exempted|Exempt)-From-Owner-Approval:/);
    }
    function showDiv(div, text) {
      div.style.display = 'inline';
      div.innerHTML = text;
    }
    function strElement(s) {
      var e = document.createElement('span');
      e.innerHTML = s;
      return e;
    }
    function showJsonLines(args, key, obj) {
      showBoldKeyValueLines(args, key, JSON.stringify(obj, null, 2));
    }
    function showBoldKeyValueLines(args, key, value) {
      args.push(c.hr(), strElement('<b>' + key + '</b>:'), c.br());
      value.split('\n').forEach(function(line) {
        args.push(c.msg(line), c.br());
      });
    }
    function showDebugMessages(result, args) {
      function addKeyValue(key, value) {
        args.push(strElement('<b>' + key + '</b>: ' + value + '<br>'));
      }
      args.push(c.hr());
      addKeyValue('changeId', changeId);
      addKeyValue('project', project);
      addKeyValue('branch', branch);
      addKeyValue('changeOwner.email', changeOwner.email);
      addKeyValue('Gerrit.url', Gerrit.url());
      addKeyValue('self.url', self.url());
      showJsonLines(args, 'changeOwner', c.change.owner);
      showBoldKeyValueLines(args, 'commit.message', message);
      showJsonLines(args, 'Client reviewers Ids', reviewerId);
      showJsonLines(args, 'Client reviewers Votes', reviewerVote);
      Object.keys(result).forEach(function(k) {
        showJsonLines(args, 'Server.' + k, result[k]);
      });
    }
    function showFilesAndOwners(result, args) {
      var sortedOwners = result.owners.map(
          function(ownerInfo) { return ownerInfo.email; });
      var groups = {};
      // group name ==> {needReviewer, needApproval, owners}
      var groupSize = {};
      // group name ==> number of files in group
      var header = emptyDiv(HEADER_DIV_ID);
      var needReviewerDiv = emptyDiv(NEED_REVIEWER_DIV_ID);
      var needApprovalDiv = emptyDiv(NEED_APPROVAL_DIV_ID);
      var hasApprovalDiv = emptyDiv(HAS_APPROVAL_DIV_ID);
      addApplyButton();
      var ownersDiv = emptyDiv(OWNERS_DIV_ID);
      var numCheckBoxes = 0;
      var owner2boxes = {}; // owner name ==> array of checkbox id
      var owner2email = {}; // owner name ==> email address
      minVoteLevel =
          ('minOwnerVoteLevel' in result ? result.minOwnerVoteLevel : 1);

      function addApplyButton() {
        var apply = c.button('Apply', {onclick: doApplyButton});
        apply.id = APPLY_BUTTON_ID;
        apply.style.display = 'none';
        args.push(apply);
      }
      function emptyDiv(id) {
        var e = document.createElement('div');
        e.id = id;
        e.style.display = 'none';
        args.push(e);
        return e;
      }
      function doApplyButton() {
        addList = [];
        removeList = [];
        // add each owner's email address to addList or removeList
        Object.keys(owner2boxes).forEach(function(owner) {
          (getElement(owner2boxes[owner][0]).checked ?
              addList : removeList).push(owner2email[owner]);
        });
        getReviewers(changeId, applyGetReviewers);
      }
      function clickBox(event) {
        var name = event.target.value;
        var checked = event.target.checked;
        var others = owner2boxes[name];
        others.forEach(function(id) { getElement(id).checked = checked; });
        getElement(APPLY_BUTTON_ID).style.display = 'inline';
      }
      function addGroupsToDiv(div, keys, title) {
        if (keys.length <= 0) {
          div.style.display = 'none';
          return;
        }
        div.innerHTML = '';
        div.style.display = 'inline';
        div.appendChild(strElement(title));
        function addOwner(ownerEmail) {
          numCheckBoxes++;
          var name = ownerEmail.replace(/@[^ ]*/g, '');
          owner2email[name] = ownerEmail;
          var id = CHECKBOX_ID + ':' + numCheckBoxes;
          if (!(name in owner2boxes)) {
            owner2boxes[name] = [];
          }
          owner2boxes[name].push(id);
          var box = c.checkbox();
          box.checked = (ownerEmail in reviewerId);
          box.id = id;
          box.value = name;
          box.onclick = clickBox;
          div.appendChild(strElement('&nbsp;&nbsp; '));
          var nobr = document.createElement('nobr');
          nobr.appendChild(box);
          nobr.appendChild(strElement(name));
          div.appendChild(nobr);
        }
        keys.forEach(function(key) {
          var owners = groups[key].owners; // string of owner emails
          var numFiles = groupSize[key];
          var item = HTML_BULLET + '&nbsp;<b>' + key + '</b>' +
              ((numFiles > 1) ? (' (' + numFiles + ' files):') : ':');
          var setOfOwners = new Set(owners.split(' '));
          function add2list(list, email) {
            if (setOfOwners.has(email)) {
              list.push(email);
            }
            return list;
          }
          div.appendChild(strElement(item));
          sortedOwners.reduce(add2list, []).forEach(addOwner);
          div.appendChild(c.br());
        });
      }
      function addOwnersDiv(div, title) {
        div.innerHTML = '';
        div.style.display = 'inline';
        div.appendChild(strElement(title));
        function compareOwnerInfo(o1, o2) {
          return o1.email.localeCompare(o2.email);
        }
        result.owners.sort(compareOwnerInfo).forEach(function(ownerInfo) {
          var email = ownerInfo.email;
          var vote = reviewerVote[email];
          if ((email in reviewerVote) && vote != 0) {
            email += ' <font color="' +
                ((vote > 0) ? 'green">(+' : 'red">(') + vote + ')</font>';
          }
          div.appendChild(strElement('&nbsp;&nbsp;' + email + '<br>'));
        });
      }
      function updateDivContent() {
        var groupNeedReviewer = [];
        var groupNeedApproval = [];
        var groupHasApproval = [];
        numCheckBoxes = 0;
        owner2boxes = {};
        Object.keys(groups).sort().forEach(function(key) {
          var g = groups[key];
          if (g.needReviewer) {
            groupNeedReviewer.push(key);
          } else if (g.needApproval) {
            groupNeedApproval.push(key);
          } else {
            groupHasApproval.push(key);
          }
        });
        showDiv(header, HTML_SELECT_REVIEWERS);
        addGroupsToDiv(needReviewerDiv, groupNeedReviewer,
                       HTML_NEED_REVIEWER_HEADER);
        addGroupsToDiv(needApprovalDiv, groupNeedApproval,
                       HTML_NEED_APPROVAL_HEADER);
        addGroupsToDiv(hasApprovalDiv, groupHasApproval,
                       HTML_HAS_APPROVAL_HEADER);
        addOwnersDiv(ownersDiv, HTML_OWNERS_HEADER);
      }
      function createGroups() {
        var owners2group = {}; // owner list to group name
        Object.keys(result.file2owners).sort().forEach(function(name) {
          var splitOwners = result.file2owners[name];
          var owners = splitOwners.join(' ');
          if (owners in owners2group) {
            groupSize[owners2group[owners]] += 1;
          } else {
            owners2group[owners] = name;
            groupSize[name] = 1;
            var needReviewer = !hasOwnerReviewer(reviewerId, splitOwners);
            var needApproval = !needReviewer &&
                !hasOwnerApproval(reviewerVote, splitOwners);
            groups[name] = {
              'needReviewer': needReviewer,
              'needApproval': needApproval,
              'owners': owners};
          }
        });
      }
      createGroups();
      updateDivContent();
    }
    function showFindOwnersResults(result) {
      function popupWindow(reviewerList) {
        setupReviewersMap(reviewerList);
        var args = [];
        if (isExemptedFromOwnerApproval()) {
          args.push(strElement(HTML_IS_EXEMPTED));
        } else if (Object.keys(result.file2owners).length <= 0) {
          args.push(strElement(HTML_NO_OWNER));
        } else {
          showFilesAndOwners(result, args);
        }
        if (result.addDebugMsg) {
          showDebugMessages(result, args);
        }
        c.popup(c.div.apply(this, args));
      }
      getReviewers(changeId, popupWindow);
    }
    function callServer(callBack) {
      // Use the plugin REST API; pass only changeId;
      // let server get current patch set, project and branch info.
      Gerrit.get('changes/' + changeId + '/owners', showFindOwnersResults);
    }
    callServer(showFindOwnersResults);
  }
  self.onAction('revision', 'find-owners', onFindOwners);
});
