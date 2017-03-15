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

package find_owners;

import com.googlecode.prolog_cafe.exceptions.PrologException;
import com.googlecode.prolog_cafe.lang.IntegerTerm;
import com.googlecode.prolog_cafe.lang.Operation;
import com.googlecode.prolog_cafe.lang.Predicate;
import com.googlecode.prolog_cafe.lang.Prolog;
import com.googlecode.prolog_cafe.lang.Term;
import com.googlesource.gerrit.plugins.findowners.Checker;

/**
 * 'check_owner_approval'(+N, R) sets R to -1, 0, or 1, if owner approval is missing, unneeded, or
 * satisfied.
 */
public class PRED_check_owner_approval_2 extends Predicate.P2 {

  public PRED_check_owner_approval_2(Term a1, Term a2, Operation n) {
    arg1 = a1;
    arg2 = a2;
    cont = n;
  }

  /** Return engine.fail() only on exceptions. */
  @Override
  public Operation exec(Prolog engine) throws PrologException {
    engine.cont = cont;
    engine.setB0();
    int n = 0; // minOwnerVoteLevel, set by Checker if (n <= 0)
    if (arg1 instanceof IntegerTerm) {
      n = ((IntegerTerm) arg1).intValue();
    }
    int result = Checker.findApproval(engine, n);
    Term a2 = arg2.dereference();
    IntegerTerm r = new IntegerTerm(result);
    return a2.unify(r, engine.trail) ? cont : engine.fail();
  }
}
