package ch.unibe.scg.regex;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.regex.MatchResult;

import ch.unibe.scg.regex.TNFAToTDFA.DFAState;
import ch.unibe.scg.regex.TransitionTable.NextDFAState;

/** Interprets the known TDFA states. Compiles missing states on the fly. */
class TDFAInterpreter {
  final Instruction.InstructionMaker instructionMaker = Instruction.InstructionMaker.get();
  final NavigableSet<DFAState> states = new TreeSet<>();

  final TransitionTable.TDFATransitionTable.Builder tdfaBuilder =
      new TransitionTable.TDFATransitionTable.Builder();
  final TNFAToTDFA tnfa2tdfa;


  TDFAInterpreter(TNFAToTDFA tnfa2tdfa) {
    this.tnfa2tdfa = tnfa2tdfa;
  }

  /** @return the range containing input. Null if there isn't one. */
  InputRange findInputRange(List<InputRange> ranges, char input) {
    int l = 0;
    int r = ranges.size() - 1;
    while (l < r) {
      final int m = (l + r) / 2;
      final char splitPoint = ranges.get(m).getFrom();
      if (splitPoint > input) {
        l = m + 1;
      } else {
        r = m - 1;
      }
    }

    final InputRange ret = ranges.get(l);
    if (!ret.contains(input)) {
      return null;
    }
    return ret;
  }

  public MatchResult interpret(CharSequence input) {
    final List<InputRange> inputRanges = tnfa2tdfa.allInputRanges();

    DFAState t = tnfa2tdfa.makeStartState();
    states.add(t);

    final MatchResult ret = new RealMatchResult();

    final int[] context = new int[100]; // TODO(niko) enter real size;
    for (int pos = 0; pos < input.length(); pos++) {
      final char a = input.charAt(pos);

      NextDFAState nextState;
      if ((nextState = tdfaBuilder.availableTransition(t, a)) != null) {
        for (final Instruction instruction : nextState.getInstructions()) {
          instruction.execute(context, pos); // TODO fill in context.
        }
        t = nextState.getNextState();
        continue;
      }

      final InputRange inputRange = findInputRange(inputRanges, a);
      if (inputRange == null) {
        return RealMatchResult.NoMatchResult.SINGLETON;
      }

      final DFAState u = tnfa2tdfa.e(t.getData(), a);

      final BitSet newLocations = tnfa2tdfa.newMemoryLocations(t.getData(), u.getData());
      // TODO(niko): There's a smarter way. You can compute the stores on the fly.

      int[] mapping = new int[tnfa2tdfa.highestMapping];
      final DFAState mappedState = tnfa2tdfa.findMappableState(states, u, mapping);

      if (mappedState == null) {
        mapping = null;
      }

      final List<Instruction> c = new ArrayList<>();
      for (int i = newLocations.nextSetBit(0); i >= 0; i = newLocations.nextSetBit(i + 1)) {
        if (mapping != null) {
          c.add(instructionMaker.storePos(mapping[i]));
        } else {
          c.add(instructionMaker.storePos(i));
        }
      }

      DFAState newState;
      if (mappedState != null) {
        c.addAll(tnfa2tdfa.mappingInstructions(mapping, u, newLocations));
        newState = mappedState;
      } else {
        states.add(u);
        newState = u;
      }

      // Free up new slots that weren't really needed.
      if (mappedState != null) {
        tnfa2tdfa.highestMapping -= newLocations.cardinality();
      }

      assert newState != null;

      tdfaBuilder.addTransition(t, inputRange, newState, c);
      t = newState;
    }

    return ret;
  }
}