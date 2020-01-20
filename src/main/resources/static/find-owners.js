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
(function (window) {
  'use strict';

  /** @enum {number} */
  const REVIEWER_STATUS = {
    ADDED: 0,
    REMOVED: 1,
  };


  /** @enum {number} */
  const GROUP_TYPE = {
    NEED_REVIEWER: 0, // no owner in Reviewers list yet
    NEED_APPROVAL: 1, // no owner Code-Review +1 yet
    STAR_APPROVED: 2, // has '*', no need of owner vote
    OWNER_APPROVED: 3, // has owner approval
    HAS_NO_OWNER: 4, // no owner at all, only shown with other types
  };

  /** @type {Object<GROUP_TYPE, string} */
  const FILE_GROUP_TITLES = {
    [GROUP_TYPE.NEED_REVIEWER]:
      'Files with owners but no owner is in the Reviewers list:',
    [GROUP_TYPE.NEED_APPROVAL]:
      'Files with owners but no Code-Review vote from an owner:',
    [GROUP_TYPE.STAR_APPROVED]:
      'Files with owners but can be approved by anyone (*):',
    [GROUP_TYPE.OWNER_APPROVED]:
      'Files with +1 or +2 Code-Review vote from owners:',
    [GROUP_TYPE.HAS_NO_OWNER]: 'Files without any named owner:',
  };

  const MIN_VOTE_LEVEL = 1;

  /**
   * Show owners of all files as suggested reviewers.
   */
  Polymer({
    is: 'gr-find-owners',

    properties: {
      change: {
        type: Object,
      },
      revision: {
        type: Object,
      },
      restApi: Object,

      // show backdrop on the overlay
      withBackdrop: {
        type: Boolean,
        value: true,
        reflectToAttribute: true,
      },
      scrollAction: {
        type: String,
        value: 'lock',
        reflectToAttribute: true,
      },

      // if overlay shows up from submit button
      fromSubmit: {
        type: Boolean,
        value: false,
      },

      /**
       * All groups on files map to owners and sections to show in the UI.
       * @type {!{
       *   sections: Array<{owners: Array, files: Array}>,
       *   title: string;
       * }}
       */
      _fileGroups: {
        type: Array,
        value() {
          return [];
        },
      },

      /**
       * Map between owner (email) and all their statuses (checked),
       * same owner share the same state across different files.
       */
      _ownerStatusMap: {
        type: Object,
        value() {
          return {};
        },
      },

      _noOwnerFound: {
        type: Boolean,
        value: false,
      },
      _showApplyBtn: {
        type: Boolean,
        value: false,
      },
      _sortedOwners: {
        type: Array,
        value() {
          return [];
        },
      },

      /** A map between reviewer email and their vote */
      _reviewerVoteMap: {
        type: Object,
        value() {
          return {};
        }
      },

      /** A map between reviewer email and raw _account_id */
      _reviewerIdMap: {
        type: Object,
        value() {
          return {};
        }
      },

      /**
       * A map to record changes made by the user, additions and deletions,
       * on any owner.
       *
       * @example
       * `{owner_email: {status: 'ADD'}}`
       */
      _reviewerChanges: {
        type: Object,
        value() {
          return {};
        }
      },

      _showDebugInfo: {
        type: Boolean,
        value: false,
      },
      _debugOwnerResponse: {
        type: Array,
        value() {
          return [];
        },
      },
    },

    behaviors: [
      Polymer.IronOverlayBehavior,
    ],

    _hasVote(email) {
      return this._reviewerVoteMap[email] &&
        this._reviewerVoteMap[email] !== 0;
    },

    _computeVoteClass(email) {
      return this._reviewerVoteMap[email] <= 0 ?
        'negative-vote' : 'positive-vote';
    },

    _retrieveVoteFrom(email) {
      const vote = this._reviewerVoteMap[email];
      return `(${vote > 0 ? `+${vote}` : vote})`;
    },

    _getOwnerId(email) {
      return this._reviewerIdMap[email];
    },

    _getOwnerName(email) {
      return email.replace(/@[^ ]*/g, '');
    },

    _hasOwnerInReviewer(email) {
      const changesOnReviewer = this._reviewerChanges[email];
      return changesOnReviewer ?
        changesOnReviewer.status === REVIEWER_STATUS.ADDED :
        this._reviewerIdMap[email];
    },

    _getOwners() {
      return this.restApi.get(`/changes/${this.change._number}/owners`);
    },

    _getReviewers() {
      return this.restApi.get(`/changes/${this.change._number}/reviewers`);
    },

    open() {
      if (!this.change || !this.revision || !this.restApi) {
        console.error('Change, revision and restApi are required!');
      }

      this._populateContent().then(() => {
        if (loadingToast) {
          loadingToast.hide();
        }
        Polymer.IronOverlayBehaviorImpl.open.apply(this);
      });
    },

    _populateContent() {
      return Promise.all([this._getOwners(), this._getReviewers()])
        .then(([ownersResponse, reviewersResponse]) => {
          const filesWithOwners = Object.keys(ownersResponse.file2owners);
          if (!filesWithOwners.length) {
            this._noOwnerFound = true;
            return;
          }

          if (ownersResponse.addDebugMsg) {
            this._populateDebug(ownersResponse);
          }

          this._generateReviewerIdAndVoteMap(ownersResponse, reviewersResponse);
          this._populateFileGroups(ownersResponse);
          this._populateOwnersList(ownersResponse);
        })
        .catch(e => {
          this._showAlert(`An error occured: ${e}`);
          console.error(e);
        });
    },

    _generateReviewerIdAndVoteMap(ownersResponse, reviewersResponse) {
      const reviewerIdMap = {};
      const reviewerVoteMap = {};
      for (const reviewer of reviewersResponse) {
        if (reviewer.email && reviewer._account_id) {
          reviewerIdMap[reviewer.email] = reviewer._account_id;
          reviewerVoteMap[reviewer.email] = 0;
          if (reviewer.approvals && reviewer.approvals['Code-Review']) {
            // The 'Code-Review' values could be "-2", "-1", " 0", "+1", "+2"
            reviewerVoteMap[reviewer.email] =
              parseInt(reviewer.approvals['Code-Review']);
          }
        }
      }

      // Give CL author a default minVoteLevel vote
      const changeOwner = this.change.owner;
      if (changeOwner != null &&
        changeOwner.email && changeOwner._account_id &&
        (!reviewerIdMap[changeOwner.email] ||
          reviewerVoteMap[changeOwner.email] === 0)) {
        reviewerIdMap[changeOwner.email] = changeOwner._account_id;
        reviewerVoteMap[changeOwner.email] =
          ownersResponse.minOwnerVoteLevel || MIN_VOTE_LEVEL;
      }

      this._reviewerVoteMap = reviewerVoteMap;
      this._reviewerIdMap = reviewerIdMap;
    },

    _populateDebug(ownersResponse) {
      this._showDebugInfo = true;
      this._debugOwnerResponse = Object.keys(ownersResponse).map(key => {
        return { key, value: ownersResponse[key] };
      });
    },

    _populateOwnersList(ownersResponse) {
      // generate sorted owners list
      const sortedOwners = ownersResponse.owners.sort((o1, o2) => {
        return o1.email.localeCompare(o2.email);
      });
      this._sortedOwners = sortedOwners.filter(owner => owner.email !== '*')
        .map(owner => owner.email);
    },

    _populateFileGroups(ownersResponse) {
      this._ownerStatusMap = {};
      this._reviewerChanges = {};

      const filesWithOwners = Object.keys(ownersResponse.file2owners);
      // generate fileGroups
      const groups = {};
      filesWithOwners.sort().forEach(file => {
        const owners = Array.from(
          new Set(ownersResponse.file2owners[file])
        ).sort();
        const ownersKey = owners.join(' ');
        let groupType;
        if (!this._hasNamedOwner(owners)) {
          groupType = GROUP_TYPE.HAS_NO_OWNER;
        } else if (this._hasOwnerApproval(
          owners, ownersResponse.minOwnerVoteLevel || MIN_VOTE_LEVEL
        )) {
          groupType = GROUP_TYPE.OWNER_APPROVED;
        } else if (this._hasStar(owners)) {
          groupType = GROUP_TYPE.STAR_APPROVED;
        } else if (!this._hasOwnerReviewer(owners)) {
          groupType = GROUP_TYPE.NEED_REVIEWER;
        } else {
          groupType = GROUP_TYPE.NEED_APPROVAL;
        }
        const group = groups[ownersKey] = groups[ownersKey] || {
          owners: owners.filter(owner => owner !== '*').map(owner => {
            this._ownerStatusMap[owner] = this._ownerStatusMap[owner] || {
              email: owner,
              name: this._getOwnerName(owner),
              checked: this._hasOwnerInReviewer(owner),
            }

            return this._ownerStatusMap[owner];
          }),
          files: [],
          type: groupType,
        };
        group.files.push(file);
      });

      // files that has no owner info
      if (ownersResponse.files.length > filesWithOwners.length) {
        const noOwnerGroup = groups['*'] = groups['*'] || {
          owners: [],
          files: [],
          type: GROUP_TYPE.HAS_NO_OWNER,
        };

        ownersResponse.files.forEach(file => {
          if (!ownersResponse.file2owners[file]) {
            noOwnerGroup.files.push(file);
          }
        });
      }

      this._fileGroups = Object.values(Object.keys(groups)
        .reduce((typeGroups, groupKey) => {
          const group = groups[groupKey];
          typeGroups[group.type] = typeGroups[group.type] || {
            title: FILE_GROUP_TITLES[group.type],
            sections: [],
          };

          const j = typeGroups[group.type].sections.length;
          typeGroups[group.type].sections.push({
            files: group.files,
            owners: group.owners
          });

          return typeGroups;
        }, {}));
    },

    _checkboxChanges($event) {
      const owner = $event.model.owner;
      const checked = $event.target.checked;
      if (this._reviewerChanges[owner.email]) {
        // Reset the change back to original
        delete this._reviewerChanges[owner.email];
      } else {
        this._reviewerChanges[owner.email] = {
          status: checked ? REVIEWER_STATUS.ADDED : REVIEWER_STATUS.REMOVED,
        };
      }

      owner.checked = checked;

      // re-compute the status for all checkboxes
      this.set('_fileGroups', this._fileGroups.slice());

      this._showApplyBtn = !!Object.keys(this._reviewerChanges).length;
    },

    /**
     * fileGroups is not used, but needed,
     * as this compute should be re-evaluated once it changed.
     */
    _computedStatusForOwner(owner, fileGroups) {
      return owner.checked;
    },

    _computeSectionTooltip(section) {
      return section.files.join(';');
    },

    _computeSectionTitle(section) {
      return `${section.files[0]} ${section.files.length > 1 ?
        `(${section.files.length} files)` : ''}:`;
    },

    _hasStar(owners) {
      return owners.some((owner) => owner === '*');
    },

    _hasNamedOwner(owners) {
      return owners.some((owner) => owner !== '*');
    },

    _hasOwnerReviewer(owners) {
      return owners.some((owner) => this._reviewerIdMap[owner]);
    },

    _hasOwnerApproval(owners, minVoteLevel) {
      let foundApproval = false;
      for (let i = 0; i < owners.length; i++) {
        const vote = this._reviewerVoteMap[owners[i]];
        if (vote === undefined) continue;
        if (vote < 0) {
          return false; // cannot have any negative vote
        }
        foundApproval |= vote >= minVoteLevel;
      }
      return foundApproval;
    },

    _isExemptedFromOwnerApproval(revision) {
      if (!revision) return false;
      return revision.commit.message.match(/(Exempted|Exempt)-From-Owner-Approval:/);
    },

    /**
     * Alias to `JSON.stringify`, always preserve whitespaces
     * @param {*} obj
     */
    stringify(obj) {
      return JSON.stringify(obj, null, 2);
    },

    _cancelChanges() {
      this.close();
    },

    _applyChanges() {
      const promises = [];
      Object.keys(this._reviewerChanges).forEach(reviewer => {
        const changeStatus = this._reviewerChanges[reviewer.status];
        if (changeStatus === REVIEWER_STATUS.ADDED) {
          promises.push(this._addReviewer(reviewer));
        } else if (changeStatus === REVIEWER_STATUS.REMOVED) {
          promises.push(this._removeReviewer(reviewer));
        }
      });

      return Promise.all(promises).then(() => {
        this._showAlert('All reviewers added!');
      }).catch(e => {
        this._showAlert(`An error occured when updating reviewers: ${e}`);
      });
    },

    _showAlert(msg) {
      document.dispatchEvent(new CustomEvent('show-alert', {
        detail: {
          message: msg,
        },
      }));
    },

    _addReviewer(email) {
      return this.restApi.post(`/changes/${this.change._number}/reviewers`, {
        'reviewer': email
      });
    },

    _removeReviewer(email) {
      return this.restApi.delete(`/changes/${this.change._number}` +
        `/reviewers/${this._reviewerIdMap[email]}`).catch(e => {
          alert(`Cannot delete reviewer: ${email}`);
        });
    },

    close() {
      Polymer.IronOverlayBehaviorImpl.close.apply(this);
    },
  });

  // refers to the gr-find-owners element
  let findOwnersEle = null;
  let loadingToast = null;
  // refers to find owners action key
  let actionKey = null;

  Gerrit.install((plugin) => {
    const restApi = plugin.restApi();

    /**
     * Shows the overlay with file and owners info.
     * @param {*} change
     * @param {*} revision
     * @param {boolean} fromSubmit
     */
    function popupFindOwnersPage(change, revision, fromSubmit) {
      if (findOwnersEle) {
        findOwnersEle.remove();
      }

      findOwnersEle = document.createElement('gr-find-owners');
      document.body.appendChild(findOwnersEle);

      findOwnersEle.change = change;
      findOwnersEle.revision = revision;
      findOwnersEle.restApi = restApi;
      findOwnersEle.fromSubmit = fromSubmit;

      loadingToast = document.createElement('gr-alert');
      loadingToast.toast = true;
      loadingToast.show('Loading owners info for this change ...', '');
      findOwnersEle.open();
    }

    if (window.Polymer) {
      plugin.on('showchange', (change, revision) => {
        const changeActions = plugin.changeActions();
        // hide previous 'Find Owners' button under 'MORE'
        changeActions.setActionHidden('revision', 'find-owners~find-owners', true);
        if (actionKey) {
          changeActions.removeTapListener(actionKey);
          changeActions.remove(actionKey);
        }
        actionKey = changeActions.add('revision', '[FIND OWNERS]');
        changeActions.setIcon(actionKey, 'robot');
        changeActions.setTitle(actionKey, 'Find owners of changed files');
        changeActions.addTapListener(actionKey,
          () => popupFindOwnersPage(change, revision, false));
      });
    } else {
      console.log('WARNING, no [FIND OWNERS] button');
    }

    // when the "Submit" button is clicked, call onSubmit
    plugin.on('submitchange', (change, revision) => {
      const OWNER_REVIEW_LABEL = 'Owner-Review-Vote';
      if (change.labels.hasOwnProperty(OWNER_REVIEW_LABEL)) {
        // pop up Find Owners page; do not submit
        popupFindOwnersPage(change, revision, true);
        return false;
      }
      return true; // okay to submit
    });

    window.addEventListener('popstate', () => {
      if (findOwnersEle) {
        findOwnersEle.remove();
        findOwnersEle = null;
      }
    });
  });
})(window);