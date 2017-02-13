// Copyright (C) 2013 The Android Open Source Project
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
    const HTML_SELECT_REVIEWERS =
        '<b>Check boxes to add/remove reviewers:<br></b>' +
        '<small>(Owners are sorted by N1, N2, N3, which are numbers ' +
        'of files owned as level 1, 2, or 3+ owner. ' +
        'Please pick owners with larger N1 values.)<br>' +
        '<b>owner-email [#files-owned,N1+N2+N3] (Code-Review vote)</b>' +
        '</small><br>';
    const HTML_NO_OWNER =
        '<b>No owner was found for changed files.</b><br>';
    const HTML_IS_EXEMPTED =
        '<b>This commit is exempted from owner approval.</b><br>';
    const HTML_ALL_HAVE_OWNER_APPROVAL =
        '<b>All files have owner approval.</b><br>';
    const HTML_EXEMPT_HINT =
        '<small><br>Add owner reviewers, or ' +
        '"<b><span style="font-size:80%;">' +
        'Exempt-From-Owner-Approval:</span></b> ' +
        '<i>reasons...</i>" in the Commit Message.</small><br>';
    const APPLY_BUTTON_NAME = 'FindOwnersApply';

    // Aliases to values in the context.
    const branch = c.change.branch;
    const changeId = c.change._number;
    const message = c.revision.commit.message;
    const project = c.change.project;

    // Variables of each click of the "Find Owners" button.
    var boxes = []; // one checkbox boxes[i] for each reviewer names[i]
    var names = []; // array of selectable reviewer email

    var reviewerId = {}; // map from a reviewer's email to account id.
    var reviewerVote = {}; // map from a reviewer's email to Code-Review vote.

    // addList and removeList are used only under applySelections.
    var addList = []; // remain emails to add to reviewers
    var removeList = []; // remain emails to remove from reviewers
    var needRefresh = false; // true if to refresh after checkAddRemoveLists

    function getReviewers(change, callBack) {
      Gerrit.get('changes/' + change + '/reviewers', callBack);
    }
    function setupReviewersMap(reviewerList) {
      reviewerId = {};
      reviewerVote = {};
      for (var i = 0; i < reviewerList.length; i++) {
        var reviewer = reviewerList[i];
        reviewerId[reviewer.email] = reviewer._account_id;
        reviewerVote[reviewer.email] =
            parseInt(reviewer.approvals['Code-Review']);
        // The 'Code-Review' values could be " 0", "+1", "-1", "+2", etc.
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
    function doApplyButton() {
      addList = [];
      removeList = [];
      for (var i = 0; i < names.length; i++) {
        var email = names[i].replace(/ .*$/, '');
        (boxes[i].checked ? addList : removeList).push(email);
      }
      getReviewers(changeId, applyGetReviewers);
    }
    function doCancelButton() {
      c.hide();
    }
    function showReviewers(result, args) {
      var included = new Set();  // who has been included
      var reviewerEmails = new Set(result.reviewers.map(
          function(name) {return name.split(' ')[0];}));
      boxes = [];
      names = [];
      function showApplyButton() {
        document.getElementById(APPLY_BUTTON_NAME).style.display = 'inline';
      }
      function addOneReviewer(name) {
        var emailAndCounters = name.split(' ');
        var email = emailAndCounters[0];
        var counters = emailAndCounters[1];
        if (!(included.has(email))) {
          included.add(email);
          var box = c.checkbox();
          box.checked = reviewerEmails.has(email);
          box.onclick = showApplyButton;
          boxes.push(box);
          names.push(name);
          var boxLabel = email + ' ' + counters;
          // append found vote value like (+2) or (-1).
          var vote = reviewerVote[email];
          if ((email in reviewerVote) && vote != 0) {
            boxLabel += ' <font color="' +
                ((vote > 0) ? 'green">(+' : 'red">(') + vote + ')</font>';
          }
          args.push(box, strElement(boxLabel + '<br>'));
        }
        return true;
      }
      args.push(strElement(HTML_SELECT_REVIEWERS));
      result.owners.map(addOneReviewer); // owners found in OWNERS files.
      result.reviewers.map(addOneReviewer); // current reviewers email + name.
    }
    function hasOwnerReviewer(reviewers, owners) {
      for (var j = 0; j < owners.length; j++) {
        if (owners[j] in reviewers || owners[j] == '*') {
          return true;
        }
      }
      return false;
    }
    function hasOwnerApproval(votes, minVoteLevel, owners) {
      var foundApproval = false;
      for (var j = 0; j < owners.length; j++) {
        if (owners[j] in votes) {
          var v = votes[owners[j]];
          if (v < 0) {
            return false; // cannot have any negative vote
          }
          // TODO: do not count if owners[j] is the patch committer.
          foundApproval |= v >= minVoteLevel;
        }
      }
      return foundApproval;
    }
    function isExemptedFromOwnerApproval() {
      // a stronger pattern: /(^|\n) *Exempted-From-Owner-Approval:[^\n]+/
      return message.match(/(Exempted|Exempt)-From-Owner-Approval:/);
    }
    function showFilesMissingOwnerReviewers(result, args) {
      // result.file2owners maps files to list of owners.
      // boxes and names are current reviewer candidates.
      var files = Object.keys(result.file2owners); // all changed files
      if (files.length <= 0) {
        // Shouldn't reach here, if Find Owners button is correctly disabled.
        args.push(c.hr(), strElement(HTML_NO_OWNER));
        return;
      }
      // Two maps from (list of owners) to (array of files).
      // L in missingOwners if nobody in L is a reviewer.
      var missingOwners = {};
      // L in missingApprovals if L is not in missingOwners and
      // nobody in L has +1 or +2 vote, or someone in L has -1 or -2 vote.
      var missingApprovals = {};
      var minVoteLevel =
          ('minOwnerVoteLevel' in result ? result['minOwnerVoteLevel'] : 1);
      function addToMap(map, key, value) {
        if (!(key in map)) {
          map[key] = [];
        }
        map[key].push(value);
      }
      for (var i = 0; i < files.length; i++) {
        var owners = result.file2owners[files[i]];
        var splitOwners = owners.split(' ');
        if (!hasOwnerReviewer(reviewerId, splitOwners)) {
          addToMap(missingOwners, owners, files[i]);
        } else if (!hasOwnerApproval(reviewerVote, minVoteLevel, splitOwners)) {
          addToMap(missingApprovals, owners, files[i]);
        }
      }
      function showFileList(missingKeys, missingMap) {
        // If multiple unreviewed files have the same owners,
        // only one of the files is shown.
        var lines = [];
        for (var i = 0; i < missingKeys.length; i++) {
          // Sort files owned by the same people, and use the first one.
          var files = missingMap[missingKeys[i]].sort();
          var msg = '<b>' + files[0] + '</b>';
          if (files.length > 1) {
            msg += ' (' + files.length + ' files)';
          }
          // Remove the domain name part of all email addresses.
          lines.push(msg + ' owned by: ' +
                     missingKeys[i].replace(/@[^ ]*/g, ''));
        }
        // Sort all output lines.
        lines.sort();
        var htmlList = '<br>';
        for (var i = 0; i < lines.length; i++) {
          // add a Black Star
          htmlList += '<small>&#x2605;</small>' + lines[i] + '<br>';
        }
        args.push(strElement(htmlList));
      }
      function showFileSection(missingMap, msg, note) {
        var missingMapKeys = Object.keys(missingMap).sort();
        if (missingMapKeys.length > 0) {
          args.push(strElement('<hr><b>' + msg + '</b>'));
          if (note.length > 0) {
            args.push(strElement('<br><small>' + note + '</small>'));
          }
          showFileList(missingMapKeys, missingMap);
        }
      }
      showFileSection(missingOwners, 'Files without owner reviewer:',
          '(need an owner in Reviewers list)');
      showFileSection(missingApprovals, 'Files without owner approval:',
          '(need +' + minVoteLevel + ' Code-Review vote from an owner)');
      if (0 == Object.keys(missingOwners).length &&
          0 == Object.keys(missingApprovals).length) {
        args.push(strElement(HTML_ALL_HAVE_OWNER_APPROVAL));
      } else {
        args.push(strElement(HTML_EXEMPT_HINT));
      }
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
      var lines = value.split('\n');
      for (var i = 0; i < lines.length; i++) {
        args.push(c.msg(lines[i]), c.br());
      }
    }
    function showDebugMessages(result, args) {
      function addKeyValue(key, value) {
        args.push(strElement('<b>' + key + '</b>: ' + value + '<br>'));
      }
      args.push(c.hr());
      addKeyValue('changeId', changeId);
      addKeyValue('project', project);
      addKeyValue('branch', branch);
      addKeyValue('Gerrit.url', Gerrit.url());
      addKeyValue('self.url', self.url());
      showJsonLines(args, 'changeOwner', c.change.owner);
      showBoldKeyValueLines(args, 'commit.message', message);
      showJsonLines(args, 'Client reviewers Ids', reviewerId);
      showJsonLines(args, 'Client reviewers Votes', reviewerVote);
      Object.keys(result).map(function(k) {
        showJsonLines(args, 'Server.' + k, result[k]);
      });
    }
    function showFindOwnersResults(result) {
      function popupWindow(reviewerList) {
        setupReviewersMap(reviewerList);
        var args = [];
        showReviewers(result, args);
        if (isExemptedFromOwnerApproval()) {
          args.push(c.hr(), strElement(HTML_IS_EXEMPTED));
        } else {
          showFilesMissingOwnerReviewers(result, args);
        }
        var apply = c.button('Apply', {onclick: doApplyButton});
        apply.id = APPLY_BUTTON_NAME;
        apply.style.display = 'none';
        args.push(apply);
        // Cancel button is useless, just click outside the window to close it.
        // args.push(c.button('Cancel', {onclick: doCancelButton}));
        if (result.addDebugMsg) {
          showDebugMessages(result, args);
        }
        c.popup(c.div.apply(this, args));
      }
      getReviewers(changeId, popupWindow);
    }
    function callServer(callBack) {
      // Use either the revision post API or plugin get API.
      // Only pass changeId, let server get current patch set,
      // project and branch info.
      c.call({change: changeId}, showFindOwnersResults);
      // self.get('change/' + changeId, showFindOwnersResults);
    }
    callServer(showFindOwnersResults);
  }
  self.onAction('revision', 'find-owners', onFindOwners);
});
