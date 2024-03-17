/**
 *  Copyright 2022 Dyte
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

import React, {PropsWithChildren} from 'react';
import {paramCase} from 'param-case';
import Link from '@docusaurus/Link';

export function Card({
                         id,
                         icon,
                         title,
                         description,
                         to,
                         tag,
                     }: PropsWithChildren<{
    id?: string;
    icon?: JSX.Element;
    title: string;
    description?: string;
    to: string;
    tag?: {
        label: string;
        color: string;
        description: string;
    };
}>) {
    return (
        <Link to={to} className="homepage-card">
            {icon && <div className="icon">{icon}</div>}
            <div className="card-content">
                <div className="title" id={id && paramCase(title)}>
                    {title}
                </div>
                {description && <div className="description">{description}</div>}
            </div>
            {tag && (
                <div className="tag absolute right-0 top-0 h-16 w-16">
          <span
              className="absolute right-[-28px] top-[-2px] w-[80px] rotate-45 transform bg-gray-600 py-1 text-center font-semibold text-white"
              style={{backgroundColor: tag.color}}
              title={tag.description}
          >
            {tag.label}
          </span>
                </div>
            )}
        </Link>
    );
}
