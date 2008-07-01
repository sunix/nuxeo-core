/*
 * (C) Copyright 2006-2008 Nuxeo SAS (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     bstefanescu
 *
 * $Id$
 */

package org.nuxeo.ecm.core.api.repository.cache;

import java.util.Iterator;
import java.util.List;

import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelIterator;

/**
 * @author <a href="mailto:bs@nuxeo.com">Bogdan Stefanescu</a>
 *
 */
public class SimpleDocumentModelIterator implements DocumentModelIterator {

    private static final long serialVersionUID = 3742039011948504441L;

    protected Iterator<DocumentModel> iterator;
    protected List<DocumentModel> list;

    /**
     *
     */
    public SimpleDocumentModelIterator(List<DocumentModel> list) {
        this.iterator = list.iterator();
        this.list = list;
    }

    public long size() {
        return list.size();
    }

    public boolean hasNext() {
        return iterator.hasNext();
    }

    public DocumentModel next() {
        return iterator.next();
    }

    public void remove() {
        iterator.remove();
    }

    public Iterator<DocumentModel> iterator() {
        return iterator;
    }

}
