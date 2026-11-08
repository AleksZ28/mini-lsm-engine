package com.ibm.internship.lsm.compaction;

import com.ibm.internship.lsm.sstable.SSTableEntry;
import com.ibm.internship.lsm.sstable.SSTableReader;

import java.util.*;

public class MergingIterator implements Iterator<SSTableEntry> {
    private record Cursor(SSTableEntry entry, int rank, Iterator<SSTableEntry> iterator) {}
    private final PriorityQueue<Cursor> heap;
    private SSTableEntry nextEntry;

    public MergingIterator(List<SSTableReader> sstables) {
        Comparator<Cursor> comparator = Comparator
                .comparing((Cursor c) -> c.entry().key())
                .thenComparingInt(Cursor::rank);

        this.heap = new PriorityQueue<>(comparator);

        for (int rank = 0; rank < sstables.size(); rank++) {
            Iterator<SSTableEntry> it = sstables.get(rank).iterator();
            if (it.hasNext()) {
                heap.add(new Cursor(it.next(), rank, it));
            }
        }


        prepareNext();

    }

    private void prepareNext() {
        nextEntry = null;
        while (!heap.isEmpty() && nextEntry == null) {
            Cursor top = heap.poll();
            String currentKey = top.entry().key();

            if (top.iterator().hasNext()) {
                heap.add(new Cursor(top.iterator().next(), top.rank(), top.iterator()));
            }

            while (!heap.isEmpty() && heap.peek().entry().key().equals(currentKey)) {
                Cursor older = heap.poll();
                if (older.iterator().hasNext()) {
                    heap.add(new Cursor(older.iterator().next(), older.rank(), older.iterator()));
                }
            }

            if (top.entry().isTombstone()) {
                continue;
            }

            nextEntry = top.entry();
        }
    }

    @Override
    public boolean hasNext() {
        return nextEntry != null;
    }

    @Override
    public SSTableEntry next() {
        if (nextEntry == null) {
            throw new NoSuchElementException();
        }
        SSTableEntry entry = nextEntry;
        prepareNext();
        return entry;
    }
}
